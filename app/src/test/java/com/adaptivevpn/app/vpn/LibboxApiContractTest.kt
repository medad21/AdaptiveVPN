package com.adaptivevpn.app.vpn

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Runs against the resolved libbox AAR on the JVM test classpath. This is deliberately a
 * reflection-only test: loading generated gomobile classes must not initialize the native core.
 * It catches API drift that ordinary app compilation cannot catch because the bridge is reflective.
 */
class LibboxApiContractTest {
    private fun bindingClass(name: String): Class<*> = try {
        Class.forName("io.nekohasekai.libbox.$name", false, javaClass.classLoader)
    } catch (e: Throwable) {
        fail("Pinned libbox 1.14.0 is missing class $name: ${e.message}")
        throw AssertionError("unreachable")
    }

    private fun requireMethods(type: Class<*>, names: Set<String>) {
        val actual = type.methods.map { it.name.lowercase() }.toSet()
        val missing = names.filter { it.lowercase() !in actual }
        assertTrue("${type.simpleName} is missing methods: ${missing.joinToString()}; actual=${actual.sorted()}", missing.isEmpty())
    }

    @Test
    fun libboxSetupAndConfigValidationMatchPinnedBinding() {
        val libbox = bindingClass("Libbox")
        val setupOptions = bindingClass("SetupOptions")
        assertTrue(
            "Libbox.setup(SetupOptions) missing",
            libbox.methods.any {
                Modifier.isStatic(it.modifiers) && it.name.equals("setup", true) &&
                    it.parameterTypes.contentEquals(arrayOf(setupOptions))
            }
        )
        requireMethods(setupOptions, setOf("setBasePath", "setWorkingPath", "setTempPath"))
        assertTrue(
            "Libbox.checkConfig(String) missing",
            libbox.methods.any {
                Modifier.isStatic(it.modifiers) && it.name.equals("checkConfig", true) &&
                    it.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
        )
    }

    @Test
    fun platformInterfaceMatchesLibbox114Contract() {
        requireMethods(bindingClass("PlatformInterface"), setOf(
            "localDNSTransport", "usePlatformAutoDetectInterfaceControl", "autoDetectInterfaceControl",
            "openTun", "useProcFS", "findConnectionOwner", "startDefaultInterfaceMonitor",
            "closeDefaultInterfaceMonitor", "getInterfaces", "underNetworkExtension", "includeAllNetworks",
            "readWIFIState", "clearDNSCache", "sendNotification", "cancelNotification",
            "startNeighborMonitor", "closeNeighborMonitor", "registerMyInterface", "usePlatformShell",
            "checkPlatformShell", "openShellSession", "lookupUser", "lookupSFTPServer",
            "readSystemSSHHostKey", "tailscaleHostname", "usePlatformBridge", "createBridge"
        ))
    }

    @Test
    fun tunOptionsAndCommandHandlerMatchLibbox114Contract() {
        requireMethods(bindingClass("TunOptions"), setOf(
            "getInet4Address", "getInet6Address", "getDNSMode", "getDNSServerAddress", "getMTU",
            "getAutoRoute", "getStrictRoute", "getInet4RouteAddress", "getInet6RouteAddress",
            "getInet4RouteExcludeAddress", "getInet6RouteExcludeAddress", "getInet4RouteRange",
            "getInet6RouteRange", "getIncludePackage", "getExcludePackage", "isHTTPProxyEnabled",
            "getHTTPProxyServer", "getHTTPProxyServerPort", "getHTTPProxyBypassDomain", "getHTTPProxyMatchDomain"
        ))
        requireMethods(bindingClass("CommandServerHandler"), setOf(
            "serviceStop", "serviceReload", "getSystemProxyStatus", "setSystemProxyEnabled",
            "triggerNativeCrash", "writeDebugMessage", "connectSSHAgent"
        ))
    }

    @Test
    fun commandServerHasUsableFactoryOrConstructor() {
        val server = bindingClass("CommandServer")
        val handler = bindingClass("CommandServerHandler")
        val platform = bindingClass("PlatformInterface")
        val factory = server.methods.any {
            Modifier.isStatic(it.modifiers) && it.name.equals("newCommandServer", true) &&
                it.parameterTypes.size == 2 && it.parameterTypes[0].isAssignableFrom(handler) &&
                it.parameterTypes[1].isAssignableFrom(platform) && server.isAssignableFrom(it.returnType)
        }
        val constructor = server.constructors.any {
            it.parameterTypes.size == 2 && it.parameterTypes[0].isAssignableFrom(handler) &&
                it.parameterTypes[1].isAssignableFrom(platform)
        }
        assertTrue("CommandServer factory/constructor signature is incompatible", factory || constructor)
    }
}
