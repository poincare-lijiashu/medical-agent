package com.medassist.common.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 可重复读的请求包装（P0-8：签名覆盖请求体）。
 *
 * <p>为什么需要它：验签必须在读取业务参数**之前**算出请求体摘要，
 * 而 Servlet 的 {@code InputStream} 只能消费一次。这里一次性读入内存并缓存，
 * 之后控制器/反序列化仍能正常读取。
 *
 * <p>容量上限：内部接口只有 JSON 小报文（医嘱/处方/计费，通常 < 64KB）。
 * 超过 {@code maxBytes} 时返回 {@code null}，由过滤器按"拒绝"处理（413 语义），
 * 避免被超大 body 打爆内存——注意**不能**退化为"跳过 body 校验"，那等于留后门。
 */
final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    private CachedBodyHttpServletRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    /**
     * 读取并缓存请求体。
     *
     * @return 包装后的请求；请求体超过 {@code maxBytes} 时返回 {@code null}
     */
    static CachedBodyHttpServletRequest wrap(HttpServletRequest request, int maxBytes)
            throws IOException {
        int limit = Math.max(1024, maxBytes);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(16 * 1024, limit));
        byte[] chunk = new byte[8 * 1024];
        int total = 0;
        try (InputStream in = request.getInputStream()) {
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > limit) {
                    return null;
                }
                buffer.write(chunk, 0, read);
            }
        }
        return new CachedBodyHttpServletRequest(request, buffer.toByteArray());
    }

    byte[] body() {
        return body;
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return in.read(b, off, len);
            }

            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("异步读取未使用缓存包装");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        String encoding = getCharacterEncoding();
        Charset charset = encoding == null || encoding.isBlank()
                ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
}
