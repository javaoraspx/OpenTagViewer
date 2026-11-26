package dev.wander.android.opentagviewer.service.web;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * 全应用共用一个 {@link OkHttpClient}。
 *
 * <p><b>一个实例，不是每次新建。</b>OkHttp 的连接池、线程池和 DNS 缓存都挂在 client 上，
 * 每个调用点 new 一个等于每次握手都重来，还会各留一组空闲线程。
 *
 * <p><b>取代了 Cronet。</b>原来的 {@code CronetProvider} 用的是 {@code cronet-embedded}，
 * 它为此带进一个 6.2 MB 的原生库。本应用只有两处网络用途：从 GitHub 拉一份 JSON
 * （筛选器/贡献者名单），以及探测用户填的 Anisette 服务器是否活着。两者都是普通 GET，
 * 不需要 QUIC，也不需要 Chromium 的网络栈。OkHttp 直接用系统 TLS 与 HTTP/2，
 * 不增加任何原生代码。
 */
public final class HttpClientProvider {

    /**
     * 超时。
     *
     * <p>短，因为两处调用都是「打开界面顺手取一下」：GitHub 那份 JSON 只有几十 KB，
     * Anisette 探测更是一个 HEAD 级别的请求。让用户对着转圈等 30 秒默认值没有意义，
     * 失败就降级（用内置的贡献者名单 / 提示服务器不可达）。
     */
    private static final long CONNECT_TIMEOUT_SECONDS = 10L;
    private static final long READ_TIMEOUT_SECONDS = 15L;

    private static volatile OkHttpClient INSTANCE;

    private HttpClientProvider() {
    }

    public static OkHttpClient getInstance() {
        OkHttpClient client = INSTANCE;
        if (client == null) {
            synchronized (HttpClientProvider.class) {
                client = INSTANCE;
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .build();
                    INSTANCE = client;
                }
            }
        }
        return client;
    }

    /** 测试用：让 JVM 测试塞进一个 MockWebServer 的 client。 */
    static void replaceForTesting(final OkHttpClient replacement) {
        INSTANCE = replacement;
    }
}
