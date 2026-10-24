package com.adaptivevpn.app.profile

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A conservative gate for the current Android VpnService adapter.
 * This is not a replacement for sing-box's own parser or a security audit.
 */
object SingBoxProfileValidator {
    const val MAX_PROFILE_BYTES = 1_048_576

    data class Result(val valid: Boolean, val summary: String, val rawJson: String? = null)

    fun validate(raw: String): Result {
        if (raw.isBlank()) return Result(false, "فایل خالی است.")
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_PROFILE_BYTES) {
            return Result(false, "حجم فایل از ۱ مگابایت بیشتر است.")
        }
        return try {
            val root = JSONObject(raw)
            val outbounds = root.optJSONArray("outbounds")
            if (outbounds == null || outbounds.length() == 0) {
                return Result(false, "آرایه outbounds وجود ندارد یا خالی است.")
            }
            var usableOutbound = false
            for (i in 0 until outbounds.length()) {
                val item = outbounds.optJSONObject(i) ?: continue
                if (item.optString("type").isNotBlank()) usableOutbound = true
            }
            if (!usableOutbound) return Result(false, "هیچ outbound با فیلد type پیدا نشد.")

            val inbounds = root.optJSONArray("inbounds")
                ?: return Result(false, "آرایه inbounds وجود ندارد؛ این فایل برای VPN کامل نیست.")
            if (inbounds.length() == 0) return Result(false, "آرایه inbounds خالی است.")

            // The current app only implements Android TUN. Accepting a SOCKS/HTTP inbound
            // alone would let the UI imply that a VPN profile is usable when it is not.
            val tunInbounds = mutableListOf<JSONObject>()
            for (i in 0 until inbounds.length()) {
                val inbound = inbounds.optJSONObject(i)
                    ?: return Result(false, "ورودی شماره ${i + 1} یک شیء JSON معتبر نیست.")
                val type = inbound.optString("type")
                if (type != "tun") {
                    return Result(false, "ورودی «$type» هنوز برای حالت VPN اندروید پشتیبانی نمی‌شود؛ فعلاً فقط پروفایل TUN پذیرفته می‌شود.")
                }
                tunInbounds += inbound
            }
            if (tunInbounds.isEmpty()) return Result(false, "پروفایل باید حداقل یک inbound با type=tun داشته باشد.")
            if (tunInbounds.size > 1) return Result(false, "در نسخه اولیه فقط یک inbound از نوع tun پشتیبانی می‌شود.")

            val tun = tunInbounds.single()
            if (!tun.optBoolean("auto_route", false)) {
                return Result(false, "برای مسیریابی ترافیک اندروید، auto_route در inbound نوع tun باید true باشد.")
            }
            val hasIPv4 = hasAddressFamily(tun, "inet4_address", "address", ipv6 = false)
            val hasIPv6 = hasAddressFamily(tun, "inet6_address", "address", ipv6 = true)
            if (!hasIPv4 || !hasIPv6) {
                return Result(false, "برای جلوگیری از خروج ناخواسته ترافیک یک خانواده IP از تونل، پروفایل اولیه باید هم آدرس IPv4 و هم IPv6 برای TUN داشته باشد؛ در sing-box جدید از فیلد address استفاده کن.")
            }
            if (tun.optString("dns_mode", "hijack").equals("disabled", ignoreCase = true)) {
                return Result(false, "dns_mode=disabled برای نسخه اولیه پذیرفته نمی‌شود؛ مسیر DNS باید صریح و قابل‌آزمایش باشد.")
            }

            val unsupportedTunFields = listOf(
                "include_uid", "exclude_uid", "include_android_user", "exclude_android_user",
                "include_package", "exclude_package", "include_interface", "exclude_interface",
                "inet4_route_exclude_address", "inet6_route_exclude_address", "route_exclude_address",
                "route_address_set", "route_exclude_address_set", "include_mac_address", "exclude_mac_address"
            )
            val presentUnsupported = unsupportedTunFields.firstOrNull { hasConfiguredValue(tun, it) }
            if (presentUnsupported != null) {
                return Result(false, "گزینه $presentUnsupported در آداپتور اندروید هنوز پیاده‌سازی نشده است؛ برای جلوگیری از نادیده‌گرفتن تنظیمات، پروفایل رد شد.")
            }
            if (tun.optBoolean("strict_route", false)) {
                return Result(false, "گزینه strict_route هنوز پیاده‌سازی نشده است؛ پروفایل رد شد تا از رفتار مسیریابی غیرمنتظره جلوگیری شود.")
            }
            if (tun.optBoolean("gso", false)) {
                return Result(false, "گزینه gso در آداپتور TUN فعلی پشتیبانی نمی‌شود.")
            }
            val httpProxyEnabled = tun.optJSONObject("platform")
                ?.optJSONObject("http_proxy")?.optBoolean("enabled", false) == true
            if (httpProxyEnabled) {
                return Result(false, "HTTP proxy سراسری در تنظیمات TUN هنوز به Android VpnService متصل نشده است.")
            }
            val dnsMode = tun.optString("dns_mode", "hijack")
            if (!dnsMode.equals("hijack", true) && !dnsMode.equals("native", true)) {
                return Result(false, "مقدار dns_mode ناشناخته است یا در نسخه اولیه پشتیبانی نمی‌شود.")
            }

            val unsupportedRouteKey = findUnsupportedOwnerRule(root)
            if (unsupportedRouteKey != null) {
                return Result(false, "قانون مسیریابی «$unsupportedRouteKey» به نگاشت فرایند/کاربر نیاز دارد که در نسخه اولیه پشتیبانی نمی‌شود.")
            }

            Result(
                true,
                "ساختار اولیه و حداقل سازگاری TUN بررسی شد؛ اعتبار نهایی هنوز باید توسط موتور sing-box و آزمایش واقعی ترافیک تأیید شود.",
                raw
            )
        } catch (_: JSONException) {
            Result(false, "فایل JSON معتبر نیست.")
        } catch (_: Exception) {
            Result(false, "خواندن یا بررسی فایل ناموفق بود.")
        }
    }

    private fun hasConfiguredValue(obj: JSONObject, key: String): Boolean {
        if (!obj.has(key) || obj.isNull(key)) return false
        return when (val value = obj.opt(key)) {
            is String -> value.isNotBlank()
            is JSONArray -> value.length() > 0
            is JSONObject -> value.length() > 0
            is Boolean -> value
            null, JSONObject.NULL -> false
            else -> true
        }
    }

    private fun hasAddressFamily(obj: JSONObject, legacyKey: String, modernKey: String, ipv6: Boolean): Boolean {
        if (hasFamilyInArray(obj.optJSONArray(legacyKey), ipv6)) return true
        return hasFamilyInArray(obj.optJSONArray(modernKey), ipv6)
    }

    private fun hasFamilyInArray(array: JSONArray?, ipv6: Boolean): Boolean {
        if (array == null) return false
        for (i in 0 until array.length()) {
            val value = array.optString(i).substringBefore('/')
            if (ipv6 && value.contains(':')) return true
            if (!ipv6 && value.contains('.') && !value.contains(':')) return true
        }
        return false
    }

    /** These selectors need process-name/path or user mapping beyond the current owner callback. */
    private fun findUnsupportedOwnerRule(root: JSONObject): String? {
        val route = root.optJSONObject("route") ?: return null
        val rules = route.optJSONArray("rules") ?: return null
        val unsupported = setOf("process_name", "process_path", "package_name", "user", "user_id", "wifi_ssid", "wifi_bssid")
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            for (key in unsupported) {
                val value = rule.opt(key)
                val isConfigured = when (value) {
                    null, JSONObject.NULL -> false
                    is String -> value.isNotBlank()
                    is JSONArray -> value.length() > 0
                    is JSONObject -> value.length() > 0
                    is Boolean -> value
                    else -> true
                }
                if (isConfigured) return key
            }
        }
        return null
    }
}
