package dev.wander.android.opentagviewer.db.repo;

import static dev.wander.android.opentagviewer.AppKeyStoreConstants.KEYSTORE_ALIAS_ACCOUNT;
import static dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore.APPLE_ACCOUNT;

import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.rxjava3.RxDataStore;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

import dev.wander.android.opentagviewer.db.AppCryptographyException;
import dev.wander.android.opentagviewer.db.repo.model.AppleUserData;
import dev.wander.android.opentagviewer.db.repo.model.UserAuthData;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.NonNull;

public class UserAuthRepository {
    private static final String TAG = UserAuthRepository.class.getSimpleName();
    private final RxDataStore<Preferences> userAccountDataStore;

    private final AppCryptographyUtil cryptography;

    /**
     * Told to ignore fields it has no interest in, explicitly.
     *
     * <p>The stored account is FindMy.py's own state mapping, and it starts with
     * {@code {"type": "account", "ids": ..., "account": ...}}. This app's model only wants
     * {@code account}, and it says so with {@code @JsonIgnoreProperties} - but that is a class
     * annotation, R8 only preserves those on classes that are kept, and these models are kept by
     * member rules alone. So at runtime the annotation was not there, and every launch after a
     * successful sign-in died with {@code Unrecognized field "type"}.
     *
     * <p>Set on the mapper rather than trusted to an annotation, because this cannot be shrunk
     * away. The proguard rule that now keeps the model classes whole is the other half: it is
     * what makes {@code @JsonProperty("account_name")} work again, which is how the account name
     * is read out of snake_case JSON.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public UserAuthRepository(@NonNull RxDataStore<Preferences> userAccountDataStore, @NonNull AppCryptographyUtil cryptography) {
        this.userAccountDataStore = userAccountDataStore;
        this.cryptography = cryptography;
    }

    public Observable<Optional<AppleUserData>> getUserAuth() {
        return userAccountDataStore.data()
            .map(settings -> {
                byte[] encryptedCombined = settings.get(APPLE_ACCOUNT);
                if (encryptedCombined != null) {
                    var result = this.decrypt(encryptedCombined);
                    var uiFacingProperties = MAPPER.readValue(result, UserAuthData.class);
                    return Optional.of(new AppleUserData(uiFacingProperties, encryptedCombined));
                }
                return Optional.<AppleUserData>empty();
            })
            .toObservable()
            .take(1)
            .subscribeOn(Schedulers.io());
    }

    public Completable clearUser() {
        return userAccountDataStore.updateDataAsync(settings -> {
            MutablePreferences mutablePreferences = settings.toMutablePreferences();
            mutablePreferences.remove(APPLE_ACCOUNT);
            return Single.just(mutablePreferences);
        })
        .subscribeOn(Schedulers.io())
        .ignoreElement();
    }

    public Completable storeUserAuth(byte[] userAuthData) {
        return userAccountDataStore.updateDataAsync(settings -> {
            MutablePreferences mutablePreferences = settings.toMutablePreferences();
            var encrypted = this.cryptography.encrypt(userAuthData, KEYSTORE_ALIAS_ACCOUNT);

            if (encrypted.getIv().length != AppCryptographyUtil.EXPECTED_IV_SIZE)
                throw new AppCryptographyException("Unexpected IV size " + encrypted.getIv().length + " when encrypting data for " + KEYSTORE_ALIAS_ACCOUNT);

            mutablePreferences.set(APPLE_ACCOUNT, encrypted.flatten());

            return Single.just(mutablePreferences);
        }).subscribeOn(Schedulers.io())
        .ignoreElement();
    }

    public byte[] decrypt(final byte[] encryptedAuthData) {
        return decrypt(encryptedAuthData, this.cryptography);
    }

    public static byte[] decrypt(final byte[] encryptedAuthData, AppCryptographyUtil cryptographyUtil) {
        var encrypted = AppCryptographyUtil.AppEncryptedData.fromFlattened(encryptedAuthData);
        return cryptographyUtil.decrypt(encrypted, KEYSTORE_ALIAS_ACCOUNT);
    }
}
