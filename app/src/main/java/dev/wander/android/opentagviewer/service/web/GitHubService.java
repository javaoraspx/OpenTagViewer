package dev.wander.android.opentagviewer.service.web;

import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.wander.android.opentagviewer.service.web.retrofit.GitHubRawUsercontentService;
import okhttp3.OkHttpClient;
import io.reactivex.rxjava3.core.Observable;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;

public class GitHubService {
    // https://square.github.io/retrofit/

    private static final String TAG = GitHubService.class.getSimpleName();

//    private static final String GITHUB_BASE_URL = "https://github.com/";
    private static final String GITHUB_RAWUSERCONTENT_BASE_URL = "https://raw.githubusercontent.com/";

    private GitHubRawUsercontentService rawUsercontentService;

    private ObjectMapper mapper = new ObjectMapper();

    public GitHubService(OkHttpClient client) {
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(GITHUB_RAWUSERCONTENT_BASE_URL)
                .addConverterFactory(JacksonConverterFactory.create())
                .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
                .client(client)
                .build();

        this.rawUsercontentService = retrofit.create(GitHubRawUsercontentService.class);
    }

    public <T> Observable<T> getJsonFileContents(final String githubFileContentsPath, Class<T> convertTo) {
        Log.d(TAG, "Calling getJsonFileContents");
        return this.rawUsercontentService.getRawJsonContent(githubFileContentsPath)
                .map(res -> mapper.readValue(res.bytes(), convertTo));
    }
}
