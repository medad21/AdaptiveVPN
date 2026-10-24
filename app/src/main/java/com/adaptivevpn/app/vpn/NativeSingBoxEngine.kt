package com.adaptivevpn.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import com.adaptivevpn.core.IpFamily
import com.adaptivevpn.core.IpRoutePrefix
import com.adaptivevpn.core.RouteCoveragePolicy
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface as JNetworkInterface
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Experimental libbox bridge. Reflection is used so the app source does not compile against
 * generated Go binding signatures; all required methods are checked at runtime and startup
 * fails closed when the installed binding differs from the expected API.
 *
 * This is an integration spike, not a substitute for building/testing against the pinned AAR.
 */
class NativeSingBoxEngine : SingBoxEngine {
    private var commandServer: Any? = null
    private var platformProxy: Any? = null
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var service: AdaptiveVpnService? = null
    private var activePlatformHandler: PlatformHandler? = null

    @Synchronized
    override fun start(context: Context, configPath: String) {
        check(commandServer == null) { "موتور از قبل شروع شده است." }
        val host = context as? AdaptiveVpnService ?: error("میزبان VPN معتبر نیست.")
        val profile = File(configPath)
        require(profile.isFile && profile.length() in 1L..1_048_576L) { "پروفایل معتبر نیست." }
        val config = profile.readText(Charsets.UTF_8)

        // Verify the minimum binding contract before mutating runtime state or opening TUN.
        val libbox = Class.forName("io.nekohasekai.libbox.Libbox")
        preflightBinding(libbox)
        setupLibbox(libbox, host)
        validateWithNativeEngine(libbox, config)
        val platformType = Class.forName("io.nekohasekai.libbox.PlatformInterface")
        val handlerType = Class.forName("io.nekohasekai.libbox.CommandServerHandler")
        val platformHandler = PlatformHandler(host)
        val platform = Proxy.newProxyInstance(platformType.classLoader, arrayOf(platformType), platformHandler)
        val handler = Proxy.newProxyInstance(handlerType.classLoader, arrayOf(handlerType), CommandHandler(host))
        val serverType = Class.forName("io.nekohasekai.libbox.CommandServer")
        val server = try {
            createCommandServer(serverType, handlerType, platformType, handler, platform)
        } catch (e: Exception) {
            platformHandler.close()
            throw e
        }
        try {
            invokeRequired(server, "start")
            val overrideType = Class.forName("io.nekohasekai.libbox.OverrideOptions")
            val overrides = overrideType.getDeclaredConstructor().newInstance()
            invokeRequired(server, "startOrReloadService", config, overrides)
        } catch (e: Exception) {
            // Roll back every partially-created resource. A failure during server.start()
            // must not leave a TUN descriptor or native command server behind.
            runCatching { invokeRequired(server, "closeService") }
            runCatching { invokeRequired(server, "close") }
            runCatching { tunDescriptor?.close() }
            tunDescriptor = null
            platformHandler.close()
            throw IllegalStateException("sing-box نتوانست پروفایل را راه‌اندازی کند: ${rootMessage(e)}", e)
        }
        commandServer = server
        platformProxy = platform
        activePlatformHandler = platformHandler
        service = host
    }

    @Synchronized
    override fun stop() {
        val server = commandServer
        commandServer = null
        if (server != null) {
            runCatching { invokeRequired(server, "closeService") }
                .onFailure { Log.w(TAG, "closeService failed", it) }
            runCatching { invokeRequired(server, "close") }
                .onFailure { Log.w(TAG, "CommandServer close failed", it) }
        }
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
        runCatching { activePlatformHandler?.close() }
        activePlatformHandler = null
        platformProxy = null
        service = null
    }


    /** Fail early with an actionable diagnostic if the pinned AAR does not match expectations. */
    private fun preflightBinding(libbox: Class<*>) {
        val methods = libbox.methods
        val setupOptionsType = Class.forName("io.nekohasekai.libbox.SetupOptions")
        val hasSetup = methods.any {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.name.equals("setup", ignoreCase = true) &&
                it.parameterTypes.contentEquals(arrayOf(setupOptionsType))
        }
        check(hasSetup) {
            val available = methods.filter { it.name.equals("setup", ignoreCase = true) }
                .joinToString { method -> method.toGenericString() }.ifBlank { "هیچ متد setup پیدا نشد" }
            "API ناسازگار libbox: امضای setup(SetupOptions) پیدا نشد. امضاهای موجود: $available."
        }
        val requiredSetupSetters = setOf("setBasePath", "setWorkingPath", "setTempPath")
        val missingSetupSetters = requiredSetupSetters.filter { required ->
            setupOptionsType.methods.none { it.name.equals(required, ignoreCase = true) &&
                it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }
        }
        check(missingSetupSetters.isEmpty()) {
            "API ناسازگار SetupOptions؛ setterهای لازم پیدا نشدند: ${missingSetupSetters.joinToString()}"
        }
        val hasNativeCheck = methods.any {
            java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                (it.name.equals("checkConfig", true) || it.name.equals("validateConfig", true)) &&
                it.parameterTypes.contentEquals(arrayOf(String::class.java))
        }
        check(hasNativeCheck) { "API ناسازگار libbox: متد بررسی تنظیمات تک‌پارامتری پیدا نشد." }
        val serverType = Class.forName("io.nekohasekai.libbox.CommandServer")
        val platformType = Class.forName("io.nekohasekai.libbox.PlatformInterface")
        val handlerType = Class.forName("io.nekohasekai.libbox.CommandServerHandler")
        // libbox 1.14 added several platform callbacks. Check the full callback surface used
        // by this adapter up front so an API drift cannot fail halfway through TUN startup.
        val requiredPlatformMethods = setOf(
            "openTun", "localDNSTransport", "usePlatformAutoDetectInterfaceControl",
            "autoDetectInterfaceControl", "useProcFS", "getInterfaces", "findConnectionOwner",
            "startDefaultInterfaceMonitor", "closeDefaultInterfaceMonitor", "underNetworkExtension",
            "includeAllNetworks", "readWIFIState", "clearDNSCache", "sendNotification",
            "cancelNotification", "startNeighborMonitor",
            "closeNeighborMonitor", "registerMyInterface", "usePlatformShell", "checkPlatformShell",
            "openShellSession", "lookupUser", "lookupSFTPServer", "readSystemSSHHostKey",
            "tailscaleHostname", "usePlatformBridge", "createBridge"
        )
        val missingPlatformMethods = requiredPlatformMethods.filter { required ->
            platformType.methods.none { it.name.equals(required, ignoreCase = true) }
        }
        check(missingPlatformMethods.isEmpty()) {
            "API ناسازگار PlatformInterface؛ callbackهای لازم پیدا نشدند: ${missingPlatformMethods.joinToString()}"
        }
        val tunOptionsType = Class.forName("io.nekohasekai.libbox.TunOptions")
        val requiredTunMethods = setOf(
            "getInet4Address", "getInet6Address", "getDNSMode", "getAutoRoute", "getStrictRoute", "getMTU",
            "getDNSServerAddress", "getInet4RouteAddress", "getInet6RouteAddress",
            "getInet4RouteExcludeAddress", "getInet6RouteExcludeAddress", "getInet4RouteRange", "getInet6RouteRange",
            "getIncludePackage", "getExcludePackage", "isHTTPProxyEnabled", "getHTTPProxyServer",
            "getHTTPProxyServerPort", "getHTTPProxyBypassDomain", "getHTTPProxyMatchDomain"
        )
        val missingTunMethods = requiredTunMethods.filter { required ->
            tunOptionsType.methods.none { it.name.equals(required, ignoreCase = true) }
        }
        check(missingTunMethods.isEmpty()) {
            "API ناسازگار TunOptions؛ getterهای لازم پیدا نشدند: ${missingTunMethods.joinToString()}"
        }
        val hasFactory = serverType.methods.any {
            java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.name.equals("newCommandServer", true) && it.parameterTypes.size == 2 &&
                it.parameterTypes[0].isAssignableFrom(handlerType) &&
                it.parameterTypes[1].isAssignableFrom(platformType) && serverType.isAssignableFrom(it.returnType)
        }
        val hasConstructor = serverType.constructors.any {
            it.parameterTypes.size == 2 && it.parameterTypes[0].isAssignableFrom(handlerType) &&
                it.parameterTypes[1].isAssignableFrom(platformType)
        }
        check(hasFactory || hasConstructor) { "API ناسازگار libbox: سازنده CommandServer پیدا نشد." }
        val requiredHandlerMethods = setOf(
            "serviceStop", "serviceReload", "getSystemProxyStatus", "setSystemProxyEnabled",
            "triggerNativeCrash", "writeDebugMessage", "connectSSHAgent"
        )
        val missingHandlerMethods = requiredHandlerMethods.filter { required ->
            handlerType.methods.none { it.name.equals(required, ignoreCase = true) }
        }
        check(missingHandlerMethods.isEmpty()) {
            "API ناسازگار CommandServerHandler؛ callbackهای لازم پیدا نشدند: ${missingHandlerMethods.joinToString()}"
        }
        Log.i(TAG, "libbox preflight passed; version=${libbox.`package`?.implementationVersion ?: "unknown"}")
    }

    /** Run libbox's own schema/semantic validation before creating any TUN interface. */
    private fun validateWithNativeEngine(libbox: Class<*>, config: String) {
        val checker = libbox.methods.firstOrNull {
            java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                (it.name.equals("checkConfig", true) || it.name.equals("validateConfig", true)) &&
                it.parameterTypes.contentEquals(arrayOf(String::class.java))
        } ?: error("API این نسخه libbox متد بررسی تنظیمات (checkConfig) را ندارد؛ اتصال متوقف شد.")
        try {
            checker.invoke(null, config)
        } catch (e: Throwable) {
            throw IllegalArgumentException("تنظیمات توسط خود sing-box رد شد: ${rootMessage(e)}", e)
        }
    }

    private fun createCommandServer(
        serverType: Class<*>, handlerType: Class<*>, platformType: Class<*>, handler: Any, platform: Any
    ): Any {
        // gomobile bindings commonly expose Go constructors as static newCommandServer(...)
        // methods rather than Java constructors. Support both forms, but never guess arguments.
        val factory = serverType.methods.firstOrNull { method ->
            java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.name.equals("newCommandServer", ignoreCase = true) &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0].isAssignableFrom(handlerType) &&
                method.parameterTypes[1].isAssignableFrom(platformType) &&
                serverType.isAssignableFrom(method.returnType)
        }
        if (factory != null) return factory.invoke(null, handler, platform)

        val constructor = serverType.constructors.firstOrNull { c ->
            c.parameterTypes.size == 2 &&
                c.parameterTypes[0].isAssignableFrom(handlerType) &&
                c.parameterTypes[1].isAssignableFrom(platformType)
        } ?: error("سازنده/کارخانه سازگار CommandServer در libbox پیدا نشد؛ API واقعی AAR با این آداپتور تطبیق ندارد.")
        return constructor.newInstance(handler, platform)
    }

    private fun setupLibbox(libbox: Class<*>, context: Context) {
        synchronized(LIBBOX_SETUP_LOCK) {
            if (libboxInitialized) return
            val baseDirectory = File(context.filesDir, "sing-box").apply { mkdirs() }
            val workingDirectory = File(baseDirectory, "work").apply { mkdirs() }
            val tempDirectory = context.cacheDir.apply { mkdirs() }
            val optionsType = Class.forName("io.nekohasekai.libbox.SetupOptions")
            val options = optionsType.getDeclaredConstructor().newInstance()
            setRequiredProperty(options, "basePath", baseDirectory.absolutePath)
            setRequiredProperty(options, "workingPath", workingDirectory.absolutePath)
            setRequiredProperty(options, "tempPath", tempDirectory.absolutePath)
            val method = libbox.methods.singleOrNull {
                java.lang.reflect.Modifier.isStatic(it.modifiers) && it.name.equals("setup", ignoreCase = true) &&
                    it.parameterTypes.contentEquals(arrayOf(optionsType))
            } ?: error("API ناسازگار libbox: امضای static setup(SetupOptions) پیدا نشد.")
            try {
                method.invoke(null, options)
                libboxInitialized = true
            } catch (e: Exception) {
                throw IllegalStateException("راه‌اندازی اولیه libbox ناموفق بود: ${rootMessage(e)}", e)
            }
        }
    }

    private fun setRequiredProperty(target: Any, name: String, value: String) {
        val suffix = name.replaceFirstChar { it.uppercase() }
        val setter = target.javaClass.methods.singleOrNull {
            it.name.equals("set$suffix", ignoreCase = true) && it.parameterCount == 1 &&
                it.parameterTypes[0] == String::class.java
        } ?: error("API libbox فاقد setter سازگار برای SetupOptions.$name است.")
        setter.invoke(target, value)
    }

    private inner class PlatformHandler(private val host: AdaptiveVpnService) : InvocationHandler {
        private val connectivity = host.getSystemService(ConnectivityManager::class.java)
        private val monitorCallbacks = IdentityHashMap<Any, ConnectivityManager.NetworkCallback>()
        private val dnsExecutor: ExecutorService = Executors.newCachedThreadPool()

        fun close() {
            synchronized(monitorCallbacks) {
                monitorCallbacks.values.forEach { callback -> runCatching { connectivity.unregisterNetworkCallback(callback) } }
                monitorCallbacks.clear()
            }
            dnsExecutor.shutdownNow()
        }

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val a = args ?: emptyArray()
            return when (method.name) {
                "toString" -> "AdaptiveVPNPlatformInterface"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === a.firstOrNull()
                "usePlatformAutoDetectInterfaceControl" -> true
                "autoDetectInterfaceControl" -> {
                    val fd = (a.firstOrNull() as? Number)?.toInt() ?: error("شناسه سوکت نامعتبر است.")
                    if (!host.protect(fd)) error("محافظت از سوکت تونل ناموفق بود.")
                    null
                }
                "openTun" -> openTun(host, a.firstOrNull() ?: error("تنظیمات TUN موجود نیست."))
                "localDNSTransport" -> createLocalDnsTransport(method.returnType)
                "startDefaultInterfaceMonitor" -> {
                    val listener = a.firstOrNull() ?: error("شنونده تغییر شبکه موجود نیست.")
                    startInterfaceMonitor(listener)
                    null
                }
                "closeDefaultInterfaceMonitor" -> {
                    val listener = a.firstOrNull() ?: return null
                    closeInterfaceMonitor(listener)
                    null
                }
                "getInterfaces" -> getInterfaces(method.returnType)
                "findConnectionOwner" -> findConnectionOwner(a)
                "useProcFS", "underNetworkExtension", "includeAllNetworks", "usePlatformShell",
                "usePlatformBridge" -> false
                // The optional feature flags above are false, so these callbacks should not be
                // reached during ordinary VPN operation. Keep their harmless lifecycle methods
                // available for the 1.14 binding contract.
                "clearDNSCache", "registerMyInterface", "closeNeighborMonitor", "startNeighborMonitor" -> null
                "readWIFIState" -> null
                "sendNotification", "cancelNotification" -> null
                // In libbox 1.14 this is a capability probe. Returning nil means the probe itself
                // succeeded; UsePlatformShell=false is what disables the feature.
                "checkPlatformShell" -> null
                "readSystemSSHHostKey", "lookupSFTPServer", "tailscaleHostname", "lookupUser", "openShellSession", "createBridge" -> error("قابلیت اختیاری پلتفرم در این نسخه پشتیبانی نمی‌شود: ${method.name}")
                else -> error("Callback ناشناخته در PlatformInterface: ${method.name}; اتصال امن متوقف شد.")
            }
        }

        private fun startInterfaceMonitor(listener: Any) {
            synchronized(monitorCallbacks) {
                if (monitorCallbacks.containsKey(listener)) return
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = notifyNetwork(listener, network)
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = notifyNetwork(listener, network, caps)
                    override fun onLost(network: Network) {
                        val replacement = findPhysicalDefaultNetwork()
                        if (replacement != null) notifyNetwork(listener, replacement)
                        else runCatching { invokeRequired(listener, "updateDefaultInterface", "", 0, false, false) }
                    }
                }
                connectivity.registerDefaultNetworkCallback(callback)
                monitorCallbacks[listener] = callback
                connectivity.activeNetwork?.let { notifyNetwork(listener, it) }
            }
        }

        private fun closeInterfaceMonitor(listener: Any) {
            synchronized(monitorCallbacks) {
                val callback = monitorCallbacks.remove(listener) ?: return
                runCatching { connectivity.unregisterNetworkCallback(callback) }
            }
        }

        private fun notifyNetwork(listener: Any, candidate: Network, knownCapabilities: NetworkCapabilities? = null) {
            val network = usableNetwork(candidate) ?: return
            val caps = knownCapabilities?.takeIf { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
                ?: connectivity.getNetworkCapabilities(network) ?: return
            val props = connectivity.getLinkProperties(network)
            val name = props?.interfaceName.orEmpty()
            val index = if (name.isBlank()) 0 else runCatching { JNetworkInterface.getByName(name)?.index ?: 0 }.getOrDefault(0)
            runCatching {
                invokeRequired(listener, "updateDefaultInterface", name, index,
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED), false)
            }.onFailure { Log.w(TAG, "Interface monitor callback failed", it) }
            val path = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            runCatching { invokeRequired(listener, "updateNetworkPath", path) }
        }

        private fun usableNetwork(candidate: Network): Network? {
            val caps = connectivity.getNetworkCapabilities(candidate)
            if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return candidate
            return findPhysicalDefaultNetwork()
        }

        private fun findPhysicalDefaultNetwork(): Network? {
            val active = connectivity.activeNetwork
            val candidates = connectivity.allNetworks.filter { network ->
                val caps = connectivity.getNetworkCapabilities(network)
                caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            return candidates.firstOrNull { it == active } ?: candidates.firstOrNull()
        }

        private fun getInterfaces(iteratorType: Class<*>): Any {
            val interfaceType = Class.forName("io.nekohasekai.libbox.NetworkInterface")
            val entries = mutableListOf<Any>()
            val javaInterfaces = runCatching { Collections.list(JNetworkInterface.getNetworkInterfaces()) }.getOrDefault(emptyList())
            for (network in connectivity.allNetworks) {
                val caps = connectivity.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                val props = connectivity.getLinkProperties(network) ?: continue
                val name = props.interfaceName ?: continue
                val javaInterface = javaInterfaces.firstOrNull { it.name == name } ?: continue
                val item = interfaceType.getDeclaredConstructor().newInstance()
                setProperty(item, "name", name)
                setProperty(item, "index", javaInterface.index)
                setProperty(item, "mtu", runCatching { javaInterface.mtu }.getOrDefault(1500))
                setProperty(item, "addresses", stringIterator(javaInterface.interfaceAddresses.mapNotNull { address ->
                    val hostAddress = address.address?.hostAddress ?: return@mapNotNull null
                    "$hostAddress/${address.networkPrefixLength}"
                }))
                setProperty(item, "dnsServer", stringIterator(props.dnsServers.mapNotNull { it.hostAddress }))
                setProperty(item, "gateway", stringIterator(props.routes.filter { it.destination.prefixLength == 0 }
                    .mapNotNull { it.gateway?.takeUnless(InetAddress::isAnyLocalAddress)?.hostAddress }))
                val typeValue = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> staticInt("InterfaceTypeWIFI", 1)
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> staticInt("InterfaceTypeCellular", 2)
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> staticInt("InterfaceTypeEthernet", 3)
                    else -> staticInt("InterfaceTypeOther", 0)
                }
                setProperty(item, "type", typeValue)
                var flags = 0
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                if (javaInterface.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
                if (javaInterface.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
                if (runCatching { javaInterface.supportsMulticast() }.getOrDefault(false)) flags = flags or OsConstants.IFF_MULTICAST
                setProperty(item, "flags", flags)
                setProperty(item, "metered", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                entries += item
            }
            val iteratorInterface = Class.forName("io.nekohasekai.libbox.NetworkInterfaceIterator")
            check(iteratorType.isAssignableFrom(iteratorInterface))
            return Proxy.newProxyInstance(iteratorInterface.classLoader, arrayOf(iteratorInterface)) { proxy, method, args ->
                when (method.name) {
                    "hasNext" -> entriesIteratorHasNext(entries, proxy)
                    "next" -> entriesIteratorNext(entries, proxy)
                    "toString" -> "AdaptiveVPNNetworkInterfaceIterator"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> error("NetworkInterfaceIterator callback پشتیبانی‌نشده: ${method.name}")
                }
            }
        }

        // Iterator state is kept per proxy so multiple sing-box iterator consumers cannot interfere.
        private val iteratorStates = IdentityHashMap<Any, Iterator<Any>>()
        private fun entriesIteratorHasNext(entries: List<Any>, proxy: Any): Boolean = synchronized(iteratorStates) {
            iteratorStates.getOrPut(proxy) { entries.iterator() }.hasNext()
        }
        private fun entriesIteratorNext(entries: List<Any>, proxy: Any): Any = synchronized(iteratorStates) {
            val iterator = iteratorStates.getOrPut(proxy) { entries.iterator() }
            check(iterator.hasNext()) { "پایان فهرست رابط‌های شبکه." }
            iterator.next()
        }

        private fun findConnectionOwner(args: Array<out Any?>): Any {
            require(args.size == 5) { "پارامترهای تشخیص مالک اتصال نامعتبرند." }
            val protocol = (args[0] as Number).toInt()
            val sourceAddress = args[1] as String
            val sourcePort = (args[2] as Number).toInt()
            val destinationAddress = args[3] as String
            val destinationPort = (args[4] as Number).toInt()
            val ownerType = Class.forName("io.nekohasekai.libbox.ConnectionOwner")
            val owner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uid = connectivity.getConnectionOwnerUid(protocol,
                    InetSocketAddress(sourceAddress, sourcePort), InetSocketAddress(destinationAddress, destinationPort))
                check(uid >= 0) { "مالک اتصال پیدا نشد." }
                ownerType.getDeclaredConstructor().newInstance().also { setProperty(it, "userId", uid) }
            } else {
                val libbox = Class.forName("io.nekohasekai.libbox.Libbox")
                invokeStaticRequired(libbox, "findConnectionOwner", protocol, sourceAddress, sourcePort, destinationAddress, destinationPort)
                    ?: error("libbox مالک اتصال را پیدا نکرد.")
            }
            val uid = runCatching { (readProperty(owner, "userId") as Number).toInt() }.getOrDefault(-1)
            val packages = if (uid >= 0) host.packageManager.getPackagesForUid(uid)?.toList().orEmpty() else emptyList()
            if (packages.isNotEmpty()) {
                runCatching { setProperty(owner, "userName", packages.first()) }
                runCatching { invokeRequired(owner, "setAndroidPackageNames", stringIterator(packages)) }
            }
            return owner
        }

        private fun createLocalDnsTransport(returnType: Class<*>): Any {
            check(returnType.isInterface) { "API ناسازگار libbox: LocalDNSTransport رابط نیست." }
            return Proxy.newProxyInstance(returnType.classLoader, arrayOf(returnType)) { proxy, method, args ->
                val a = args ?: emptyArray()
                when (method.name) {
                    "raw" -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    "exchange" -> {
                        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "DNS خام فقط از Android 10 به بعد پشتیبانی می‌شود." }
                        exchangeDns(a[0] ?: error("ExchangeContext موجود نیست."), a[1] as ByteArray)
                        null
                    }
                    "lookup" -> {
                        lookupDns(a[0] ?: error("ExchangeContext موجود نیست."), a.getOrNull(1)?.toString().orEmpty(), a.getOrNull(2)?.toString().orEmpty())
                        null
                    }
                    "toString" -> "AdaptiveVPNLocalDNSTransport"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === a.firstOrNull()
                    else -> error("LocalDNSTransport callback پشتیبانی‌نشده: ${method.name}")
                }
            }
        }

        private fun exchangeDns(exchangeContext: Any, message: ByteArray) {
            val network = findPhysicalDefaultNetwork() ?: error("شبکه فیزیکی برای DNS پیدا نشد.")
            val latch = CountDownLatch(1)
            val cancellation = CancellationSignal()
            var failure: Throwable? = null
            val callback = object : DnsResolver.Callback<ByteArray>() {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    try {
                        if (rcode == 0) invokeRequired(exchangeContext, "rawSuccess", answer)
                        else invokeRequired(exchangeContext, "errorCode", rcode)
                    } catch (e: Throwable) { failure = e } finally { latch.countDown() }
                }
                override fun onError(error: DnsResolver.DnsException) {
                    failure = error.cause ?: error
                    if (error.cause is ErrnoException) runCatching { invokeRequired(exchangeContext, "errnoCode", (error.cause as ErrnoException).errno) }
                    latch.countDown()
                }
            }
            DnsResolver.getInstance().rawQuery(network, message, DnsResolver.FLAG_NO_RETRY, dnsExecutor, cancellation, callback)
            awaitDns(latch, cancellation) { failure }
        }

        private fun lookupDns(exchangeContext: Any, networkName: String, domain: String) {
            require(domain.isNotBlank()) { "نام دامنه برای DNS خالی است." }
            val network = findPhysicalDefaultNetwork() ?: error("شبکه فیزیکی برای DNS پیدا نشد.")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val answer = try { network.getAllByName(domain).mapNotNull { it.hostAddress }.joinToString("\n") }
                catch (e: Exception) {
                    invokeRequired(exchangeContext, "errorCode", 3)
                    return
                }
                invokeRequired(exchangeContext, "success", answer)
                return
            }
            val type = when {
                networkName.endsWith("4") -> DnsResolver.TYPE_A
                networkName.endsWith("6") -> DnsResolver.TYPE_AAAA
                else -> null
            }
            val latch = CountDownLatch(1)
            val cancellation = CancellationSignal()
            var failure: Throwable? = null
            val callback = object : DnsResolver.Callback<Collection<InetAddress>>() {
                override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                    try {
                        if (rcode == 0) invokeRequired(exchangeContext, "success", answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                        else invokeRequired(exchangeContext, "errorCode", rcode)
                    } catch (e: Throwable) { failure = e } finally { latch.countDown() }
                }
                override fun onError(error: DnsResolver.DnsException) {
                    failure = error.cause ?: error
                    if (error.cause is ErrnoException) runCatching { invokeRequired(exchangeContext, "errnoCode", (error.cause as ErrnoException).errno) }
                    latch.countDown()
                }
            }
            if (type != null) DnsResolver.getInstance().query(network, domain, type, DnsResolver.FLAG_NO_RETRY, dnsExecutor, cancellation, callback)
            else DnsResolver.getInstance().query(network, domain, DnsResolver.FLAG_NO_RETRY, dnsExecutor, cancellation, callback)
            awaitDns(latch, cancellation) { failure }
        }

        private fun awaitDns(latch: CountDownLatch, cancellation: CancellationSignal, failure: () -> Throwable?) {
            if (!latch.await(8, TimeUnit.SECONDS)) {
                cancellation.cancel()
                error("درخواست DNS پس از ۸ ثانیه پاسخ نداد.")
            }
            failure()?.let { throw IllegalStateException("درخواست DNS ناموفق بود: ${it.message ?: it.javaClass.simpleName}", it) }
        }

        private fun stringIterator(values: List<String>): Any {
            val type = Class.forName("io.nekohasekai.libbox.StringIterator")
            val iterator = values.iterator()
            return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "hasNext" -> iterator.hasNext()
                    "next" -> iterator.next()
                    "len" -> values.size
                    "toString" -> "AdaptiveVPNStringIterator"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> error("StringIterator callback پشتیبانی‌نشده: ${method.name}")
                }
            }
        }

        private fun setProperty(target: Any, name: String, value: Any) {
            val suffix = name.replaceFirstChar { it.uppercase() }
            val setter = target.javaClass.methods.singleOrNull { it.name.equals("set$suffix", ignoreCase = true) && it.parameterCount == 1 &&
                (boxed(it.parameterTypes[0]).isAssignableFrom(value.javaClass) ||
                    (it.parameterTypes[0].isPrimitive && boxed(it.parameterTypes[0]).isAssignableFrom(value.javaClass))) }
                ?: error("API libbox فاقد setter سازگار برای $name است.")
            setter.invoke(target, value)
        }

        private fun staticInt(field: String, fallback: Int): Int = runCatching {
            Class.forName("io.nekohasekai.libbox.Libbox").getField(field).getInt(null)
        }.getOrDefault(fallback)

        private fun boxed(type: Class<*>): Class<*> = when (type) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            else -> type
        }
    }

    private fun invokeStaticRequired(type: Class<*>, name: String, vararg args: Any?): Any? {
        fun boxed(type: Class<*>): Class<*> = when (type) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            else -> type
        }
        val candidates = type.methods.filter { method ->
            java.lang.reflect.Modifier.isStatic(method.modifiers) && method.name == name && method.parameterCount == args.size &&
                method.parameterTypes.zip(args).all { (parameter, arg) ->
                    if (arg == null) !parameter.isPrimitive else boxed(parameter).isAssignableFrom(arg.javaClass)
                }
        }
        val method = candidates.singleOrNull()
            ?: error(if (candidates.isEmpty()) "متد static سازگار در libbox پیدا نشد: $name/${args.size}"
                else "چند overload مبهم برای متد static libbox پیدا شد: $name/${args.size}")
        return method.invoke(null, *args)
    }

    private fun openTun(host: AdaptiveVpnService, options: Any): Int {
        check(tunDescriptor == null) { "رابط TUN قبلی هنوز بسته نشده است." }
        val builder = host.Builder().setSession("AdaptiveVPN")
        val mtu = runCatching { readProperty(options, "mtu") as Number }.getOrNull()?.toInt() ?: 1500
        require(mtu in 576..9000) { "MTU نامعتبر است." }
        builder.setMtu(mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val physicalNetwork = findPhysicalDefaultNetwork(host)
            val connectivity = host.getSystemService(ConnectivityManager::class.java)
            val capabilities = physicalNetwork?.let { connectivity.getNetworkCapabilities(it) }
            builder.setMetered(capabilities != null && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        }

        val ipv4Addresses = addAddresses(builder, options, "inet4Address")
        val ipv6Addresses = addAddresses(builder, options, "inet6Address")
        require(ipv4Addresses > 0 && ipv6Addresses > 0) {
            "برای نسخه اولیه باید هر دو خانواده IPv4 و IPv6 در TUN تعریف شده باشند."
        }

        val autoRoute = runCatching { readProperty(options, "autoRoute") as Boolean }
            .getOrElse { throw IllegalStateException("گزینه autoRoute در API libbox در دسترس نیست.", it) }
        require(autoRoute) { "این آداپتور فقط پروفایل‌هایی با auto_route فعال را پشتیبانی می‌کند." }
        addDnsServers(builder, options)
        val ipv4Routes = addRoutes(builder, options, "inet4RouteAddress", "inet4RouteRange")
        val ipv6Routes = addRoutes(builder, options, "inet6RouteAddress", "inet6RouteRange")
        // The initial release is full-tunnel only. If custom routes do not cover an entire
        // address family, add the default route rather than silently allowing bypass traffic.
        if (!ipv4Routes.coversEntireFamily) builder.addRoute("0.0.0.0", 0)
        if (!ipv6Routes.coversEntireFamily) builder.addRoute("::", 0)
        val established = builder.establish() ?: error("Android رابط TUN را ایجاد نکرد.")
        tunDescriptor = established
        return established.fd
    }

    private fun findPhysicalDefaultNetwork(host: AdaptiveVpnService): Network? {
        val connectivity = host.getSystemService(ConnectivityManager::class.java)
            ?: error("Android ConnectivityManager در دسترس نیست.")
        val active = connectivity.activeNetwork
        val candidates = connectivity.allNetworks.filter { network ->
            val caps = connectivity.getNetworkCapabilities(network)
            caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        return candidates.firstOrNull { it == active } ?: candidates.firstOrNull()
    }

    private fun addAddresses(builder: VpnService.Builder, options: Any, property: String): Int {
        val iterator = readProperty(options, property) ?: return 0
        var count = 0
        while (hasNext(iterator)) {
            val item = next(iterator) ?: break
            val address = readProperty(item, "address")?.toString() ?: error("آدرس TUN نامعتبر است.")
            val prefix = (readProperty(item, "prefix") as? Number)?.toInt() ?: error("prefix آدرس TUN نامعتبر است.")
            builder.addAddress(address, prefix)
            count++
        }
        return count
    }

    private fun addDnsServers(builder: VpnService.Builder, options: Any) {
        val iterator = readProperty(options, "dnsServerAddress")
            ?: error("libbox برای حالت DNS فعال، هیچ DNS server address برنگرداند.")
        var count = 0
        while (hasNext(iterator)) {
            val address = next(iterator)?.toString() ?: error("نشانی DNS خالی است.")
            builder.addDnsServer(address)
            count++
        }
        require(count > 0) { "فهرست DNS خالی است؛ برای جلوگیری از نشت DNS، تونل ساخته نشد." }
    }

    private fun addRoutes(
        builder: VpnService.Builder, options: Any, preferred: String, fallback: String
    ): RouteCoveragePolicy.Result {
        val prefixes = mutableListOf<IpRoutePrefix>()
        fun consume(iterator: Any) {
            while (hasNext(iterator)) {
                val item = next(iterator) ?: break
                val address = readProperty(item, "address")?.toString() ?: error("نشانی route نامعتبر است.")
                val prefix = (readProperty(item, "prefix") as? Number)?.toInt()
                    ?: error("prefix route نامعتبر است.")
                prefixes += IpRoutePrefix(address, prefix)
            }
        }
        // Both getters are part of the pinned contract. Do not swallow a missing/broken getter,
        // because doing so could omit configured routes and create an unintended bypass.
        consume(readProperty(options, preferred) ?: error("فهرست route $preferred موجود نیست."))
        consume(readProperty(options, fallback) ?: error("فهرست route $fallback موجود نیست."))
        val family = if (preferred.startsWith("inet6", ignoreCase = true)) IpFamily.IPV6 else IpFamily.IPV4
        val coverage = RouteCoveragePolicy.evaluate(prefixes, family)
        prefixes.forEach { builder.addRoute(it.address, it.prefixLength) }
        return coverage
    }

    private inner class CommandHandler(private val host: AdaptiveVpnService) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "toString" -> "AdaptiveVPNCommandServerHandler"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "serviceStop" -> { stop(); host.stopSelf(); null }
            "serviceReload" -> error("بارگذاری مجدد از رابط کاربری هنوز در دسترس نیست.")
            "getSystemProxyStatus" -> method.returnType.getDeclaredConstructor().newInstance()
            "setSystemProxyEnabled" -> error("تغییر system proxy در AdaptiveVPN پشتیبانی نمی‌شود.")
            "writeDebugMessage" -> null
            "triggerNativeCrash" -> error("این عملیات از برنامه در دسترس نیست.")
            "connectSSHAgent" -> -1
            else -> error("Callback پشتیبانی‌نشده در CommandServerHandler: ${method.name}")
        }
    }

    private fun readProperty(target: Any, name: String): Any? {
        val suffix = name.replaceFirstChar { it.uppercase() }
        val getter = target.javaClass.methods.firstOrNull { it.parameterCount == 0 &&
            (it.name.equals("get$suffix", ignoreCase = true) || it.name.equals(name, ignoreCase = true)) }
            ?: error("API libbox فاقد ویژگی موردنیاز $name است.")
        return getter.invoke(target)
    }

    private fun hasNext(iterator: Any): Boolean = (invokeRequired(iterator, "hasNext") as? Boolean) == true
    private fun next(iterator: Any): Any? = invokeRequired(iterator, "next")

    private fun invokeRequired(target: Any, name: String, vararg args: Any?): Any? {
        fun boxed(type: Class<*>): Class<*> = when (type) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            else -> type
        }
        val candidates = target.javaClass.methods.filter { method ->
            method.name == name && method.parameterCount == args.size &&
                method.parameterTypes.zip(args).all { (parameter, arg) ->
                    if (arg == null) !parameter.isPrimitive else boxed(parameter).isAssignableFrom(arg.javaClass)
                }
        }
        val method = candidates.singleOrNull()
            ?: error(if (candidates.isEmpty()) "متد سازگار در libbox پیدا نشد: $name/${args.size}"
                     else "چند overload مبهم برای متد libbox پیدا شد: $name/${args.size}")
        return method.invoke(target, *args)
    }

    private fun rootMessage(error: Throwable): String {
        var current = error
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current.message ?: current.javaClass.simpleName
    }

    companion object {
        private const val TAG = "AdaptiveVPNEngine"
        private val LIBBOX_SETUP_LOCK = Any()
        @Volatile private var libboxInitialized = false
    }
}
