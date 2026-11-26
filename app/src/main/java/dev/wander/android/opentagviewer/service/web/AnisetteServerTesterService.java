package dev.wander.android.opentagviewer.service.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.Data;
import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.http.GET;

public class AnisetteServerTesterService {

    private final OkHttpClient client;

    public AnisetteServerTesterService(OkHttpClient client) {
        this.client = client;
    }

    public Observable<AnisetteServerRootData> getIndex(final String anisetteServerRootUrl) {
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(anisetteServerRootUrl)
                .addConverterFactory(JacksonConverterFactory.create())
                .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
                .client(client)
                .build();

        var service = retrofit.create(AnisetteServer.class);

        return service.getRoot().subscribeOn(Schedulers.io());
    }

    @Data
    public static class AnisetteServerRootData {

        @JsonProperty("X-Apple-I-Client-Time")
        private String appleIClientTime;

        @JsonProperty("X-Apple-I-MD")
        private String appleIMd;

        @JsonProperty("X-Apple-I-MD-LU")
        private String appleIMdLu;

        @JsonProperty("X-Apple-I-MD-M")
        private String appleIMdM;

        @JsonProperty("X-Apple-I-MD-RINFO")
        private String appleIMdRinfo;

        @JsonProperty("X-Apple-I-SRL-NO")
        private String appleISrlNo;

        @JsonProperty("X-Apple-I-TimeZone")
        private String appleITimeZone;

        @JsonProperty("X-Apple-Locale")
        private String appleLocale;

        @JsonProperty("X-MMe-Client-Info")
        private String mmeClientInfo;

        @JsonProperty("X-Mme-Device-Id")
        private String mmeDeviceId;
    }

    interface AnisetteServer {
        @GET("/")
        Observable<AnisetteServerRootData> getRoot();
    }
}
