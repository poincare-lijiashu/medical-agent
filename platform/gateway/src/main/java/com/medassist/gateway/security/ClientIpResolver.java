package com.medassist.gateway.security;

import com.medassist.gateway.config.GatewayAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 真实客户端 IP 解析（防 X-Forwarded-For 伪造，M3）。
 *
 * <p>问题背景：Spring Cloud Gateway 对 {@code X-Forwarded-For} 是**追加**语义，
 * 客户端自带首段即可自选"来源 IP"，导致按 IP 的登录限速失效、审计与在线设备列表被投毒。
 *
 * <p>解析规则（业界通行的"可信跳"算法）：
 * <ol>
 *   <li>未配置 {@code trusted-proxies} → <b>完全不信任 XFF</b>，直接取 TCP 对端地址（安全默认）；</li>
 *   <li>对端地址不在可信网段内 → 同样忽略 XFF（说明请求没有经过我们的代理）；</li>
 *   <li>对端可信 → 从 XFF 右侧向左跳过可信代理，遇到的第一个非可信地址即真实客户端；</li>
 *   <li>解析失败一律回退到对端地址，绝不回退到客户端可控字符串。</li>
 * </ol>
 *
 * <p>结果由 {@link com.medassist.gateway.filter.AuthGlobalFilter} 写入 {@code X-Client-Ip}，
 * 下游 auth 只读该头，不再自行解析 XFF。
 */
@Component
public class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);
    private static final int MAX_LEN = 64;

    private final List<Cidr> trusted;

    public ClientIpResolver(GatewayAuthProperties props) {
        this.trusted = parseCidrs(props.clientIp() == null ? List.of() : props.clientIp().trustedProxies());
        if (trusted.isEmpty()) {
            log.info("gateway.client-ip.mode=direct（未配置 trusted-proxies，忽略 X-Forwarded-For）");
        } else {
            log.info("gateway.client-ip.mode=trusted-proxy cidrs={}", trusted.size());
        }
    }

    /** @return 真实客户端 IP 字符串；无法判定时返回 {@code "unknown"} */
    public String resolve(InetSocketAddress remoteAddress, String forwardedFor) {
        String peer = hostOf(remoteAddress);
        if (trusted.isEmpty() || peer == null || !isTrusted(peer)) {
            return truncate(peer == null ? "unknown" : peer);
        }
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return truncate(peer);
        }
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String candidate = normalize(hops[i]);
            if (candidate == null) {
                continue;
            }
            if (!isTrusted(candidate)) {
                return truncate(candidate);
            }
        }
        // 全部跳都在可信网段内：取最左侧（最早写入）的地址作为客户端
        String leftmost = normalize(hops[0]);
        return truncate(leftmost == null ? peer : leftmost);
    }

    // ---------------------------------------------------------------- 内部方法

    private static String hostOf(InetSocketAddress address) {
        if (address == null || address.getAddress() == null) {
            return null;
        }
        return address.getAddress().getHostAddress();
    }

    /** 只接受 IPv4/IPv6 字面量，避免把主机名交给 DNS 解析（可能被用于探测内网）。 */
    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > 45) {
            return null;
        }
        if (!value.matches("[0-9a-fA-F:.]+")) {
            return null;
        }
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isTrusted(String ip) {
        byte[] candidate = bytes(ip);
        if (candidate == null) {
            return false;
        }
        for (Cidr cidr : trusted) {
            if (cidr.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] bytes(String ip) {
        try {
            return InetAddress.getByName(ip).getAddress();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Cidr> parseCidrs(List<String> raw) {
        List<Cidr> result = new ArrayList<>();
        for (String item : raw) {
            if (item == null || item.isBlank()) {
                continue;
            }
            Cidr cidr = Cidr.parse(item.trim());
            if (cidr == null) {
                // 配置错误必须显式暴露：静默忽略会让人误以为 XFF 已被信任
                throw new IllegalStateException("非法的 trusted-proxies 配置项：" + item
                        + "（应为 CIDR，如 10.0.0.0/8 或 192.168.1.5/32）");
            }
            result.add(cidr);
        }
        return result;
    }

    private static String truncate(String value) {
        return value.length() <= MAX_LEN ? value : value.substring(0, MAX_LEN);
    }

    /** 极简 CIDR（IPv4/IPv6 通用按位比较）。 */
    private record Cidr(byte[] network, int prefixLength) {

        static Cidr parse(String value) {
            String[] parts = value.split("/");
            byte[] addr;
            try {
                addr = InetAddress.getByName(parts[0]).getAddress();
            } catch (Exception e) {
                return null;
            }
            int prefix = addr.length * 8;
            if (parts.length == 2) {
                try {
                    prefix = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    return null;
                }
            } else if (parts.length > 2) {
                return null;
            }
            if (prefix < 0 || prefix > addr.length * 8) {
                return null;
            }
            return new Cidr(addr, prefix);
        }

        boolean contains(byte[] candidate) {
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
