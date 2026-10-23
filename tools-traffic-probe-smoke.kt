import com.adaptivevpn.app.vpn.TrafficProbe

fun main() {
    check(TrafficProbe.isExpectedResponse("google", 204, ""))
    check(!TrafficProbe.isExpectedResponse("google", 200, ""))
    check(TrafficProbe.isExpectedResponse("cloudflare", 200, "fl=abc\nip=203.0.113.4\n"))
    check(!TrafficProbe.isExpectedResponse("cloudflare", 200, "captive portal"))
    check(!TrafficProbe.isExpectedResponse("unknown", 200, "ip=203.0.113.4"))
    println("PASS: HTTPS traffic-probe response policy and captive-portal rejection")
}
