package info.guardianproject.keanu.matrix.plugin;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.matrix.androidsdk.HomeServerConnectionConfig;
import org.matrix.androidsdk.MXDataHandler;
import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.core.JsonUtils;
import org.matrix.androidsdk.core.callback.ApiCallback;
import org.matrix.androidsdk.core.callback.SimpleApiCallback;
import org.matrix.androidsdk.core.model.MatrixError;
import org.matrix.androidsdk.crypto.IncomingRoomKeyRequest;
import org.matrix.androidsdk.crypto.IncomingRoomKeyRequestCancellation;
import org.matrix.androidsdk.crypto.MXDecryptionException;
import org.matrix.androidsdk.crypto.MXEventDecryptionResult;
import org.matrix.androidsdk.crypto.MXCrypto;
import org.matrix.androidsdk.crypto.RoomKeysRequestListener;
import org.matrix.androidsdk.crypto.data.MXDeviceInfo;
import org.matrix.androidsdk.crypto.data.MXOlmSessionResult;
import org.matrix.androidsdk.crypto.data.MXUsersDevicesMap;
import org.matrix.androidsdk.crypto.model.crypto.EncryptedFileInfo;
import org.matrix.androidsdk.data.MyUser;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.data.store.IMXStore;
import org.matrix.androidsdk.data.store.IMXStoreListener;
import org.matrix.androidsdk.listeners.IMXEventListener;
import org.matrix.androidsdk.listeners.MXMediaUploadListener;
import org.matrix.androidsdk.rest.client.LoginRestClient;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.PowerLevels;
import org.matrix.androidsdk.rest.model.ReceiptData;
import org.matrix.androidsdk.rest.model.RoomMember;
import org.matrix.androidsdk.rest.model.User;
import org.matrix.androidsdk.rest.model.bingrules.BingRule;
import org.matrix.androidsdk.rest.model.login.Credentials;
import org.matrix.androidsdk.rest.model.login.RegistrationFlowResponse;
import org.matrix.androidsdk.rest.model.login.RegistrationParams;
import org.matrix.androidsdk.rest.model.message.AudioMessage;
import org.matrix.androidsdk.rest.model.message.FileMessage;
import org.matrix.androidsdk.rest.model.message.ImageMessage;
import org.matrix.androidsdk.rest.model.message.VideoMessage;
import org.matrix.androidsdk.rest.model.search.SearchUsersResponse;
import org.matrix.androidsdk.rest.model.sync.AccountDataElement;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import info.guardianproject.iocipher.File;
import info.guardianproject.iocipher.FileInputStream;
import info.guardianproject.keanu.core.Preferences;
import info.guardianproject.keanu.core.model.ChatGroup;
import info.guardianproject.keanu.core.model.ChatGroupManager;
import info.guardianproject.keanu.core.model.ChatSession;
import info.guardianproject.keanu.core.model.ChatSessionManager;
import info.guardianproject.keanu.core.model.ConnectionListener;
import info.guardianproject.keanu.core.model.Contact;
import info.guardianproject.keanu.core.model.ContactListManager;
import info.guardianproject.keanu.core.model.ImConnection;
import info.guardianproject.keanu.core.model.ImEntity;
import info.guardianproject.keanu.core.model.ImErrorInfo;
import info.guardianproject.keanu.core.model.ImException;
import info.guardianproject.keanu.core.model.Invitation;
import info.guardianproject.keanu.core.model.Message;
import info.guardianproject.keanu.core.model.Presence;
import info.guardianproject.keanu.core.model.Server;
import info.guardianproject.keanu.core.model.impl.BaseAddress;
import info.guardianproject.keanu.core.provider.Imps;
import info.guardianproject.keanu.core.service.IChatSession;
import info.guardianproject.keanu.core.service.IContactListListener;
import info.guardianproject.keanu.core.service.adapters.ChatSessionAdapter;
import info.guardianproject.keanu.core.util.DatabaseUtils;
import info.guardianproject.keanu.core.util.Debug;
import info.guardianproject.keanu.core.util.SecureMediaStore;
import info.guardianproject.keanu.matrix.R;
import okhttp3.Dns;

import static info.guardianproject.keanu.core.KeanuConstants.DEFAULT_AVATAR_HEIGHT;
import static info.guardianproject.keanu.core.KeanuConstants.DEFAULT_AVATAR_WIDTH;
import static info.guardianproject.keanu.core.util.DatabaseUtils.ROOM_AVATAR_ACCESS;
import static org.matrix.androidsdk.rest.model.Event.EVENT_TYPE_MESSAGE_ENCRYPTED;

public class MatrixConnection extends ImConnection {

    private MXSession mSession;
    private MXDataHandler mDataHandler;
    private LoginRestClient mLoginRestClient;

    protected KeanuMXFileStore mStore = null;
    private Credentials mCredentials = null;

    private HomeServerConnectionConfig mConfig;

    private long mProviderId = -1;
    private long mAccountId = -1;
    private Contact mUser = null;

    private String mDeviceName = null;

    private HashMap<String, String> mSessionContext = new HashMap<>();
    private MatrixChatSessionManager mChatSessionManager;
    private MatrixContactListManager mContactListManager;
    private MatrixChatGroupManager mChatGroupManager;

    private final static String TAG = "MATRIX";
    private static final int THREAD_ID = 10001;

    private final static String HTTPS_PREPEND = "https://";

    private ExecutorService mExecutor = null;

    private final static long TIME_ONE_DAY_MS = 1000 * 60 * 60 * 24;


    public final static String EVENT_TYPE_REACTION = "m.reaction";

    private Server[] mServers;

    private RegistrationManager mRm;
    private RegistrationManager.RegistrationListener mRegListener;


    // Request Handler
    @Nullable
    private KeyRequestHandler mKeyRequestHandler;


    public MatrixConnection(Context context) {
        super(context);

        mContactListManager = new MatrixContactListManager(context, this);

        mChatSessionManager = new MatrixChatSessionManager(context, this);
        mChatGroupManager = new MatrixChatGroupManager(context, this, mChatSessionManager);

        mExecutor = new ThreadPoolExecutor( 2, 2, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

        mServers = Server.getServers(context);

    }

    @Override
    public Contact getLoginUser() {
        return mUser;
    }

    @Override
    public int[] getSupportedPresenceStatus() {
        return new int[0];
    }

    @Override
    public void initUser(long providerId, long accountId) throws ImException {

        ContentResolver contentResolver = mContext.getContentResolver();

        Cursor cursor = contentResolver.query(Imps.ProviderSettings.CONTENT_URI,
                new String[]{Imps.ProviderSettings.NAME, Imps.ProviderSettings.VALUE},
                Imps.ProviderSettings.PROVIDER + "=?",
                new String[]{Long.toString(providerId)}, null);

        if (cursor == null)
            throw new ImException("unable to query settings");

        Imps.ProviderSettings.QueryMap providerSettings = new Imps.ProviderSettings.QueryMap(
                cursor, contentResolver, providerId, false, null);

        mProviderId = providerId;
        mAccountId = accountId;
        mUser = makeUser(providerSettings, contentResolver);
        mUserPresence = new Presence(Presence.AVAILABLE, null, Presence.CLIENT_TYPE_MOBILE);

        mDeviceName = providerSettings.getDeviceName();

        providerSettings.close();

        initMatrix();

        initAvatarLoader();
        initMessageLoader();
    }

    @Override
    public void networkTypeChanged() {
        super.networkTypeChanged();

        if (mSession != null)
            mSession.setIsOnline(true);
    }

    private Contact makeUser(Imps.ProviderSettings.QueryMap providerSettings, ContentResolver contentResolver) {
        String nickname = Imps.Account.getNickname(contentResolver, mAccountId);
        String userName = Imps.Account.getUserName(contentResolver, mAccountId);
        String domain = providerSettings.getDomain();

        return new Contact(new MatrixAddress('@' + userName + ':' + domain), nickname, Imps.Contacts.TYPE_NORMAL);
    }

    @Override
    public int getCapability() {
        return 0;
    }

    @Override
    public void loginAsync(final long accountId, final String password, final long providerId, boolean retry) {

        if (mDataHandler == null || (!mDataHandler.isAlive()))
            return;

        setState(LOGGING_IN, null);

        mProviderId = providerId;
        mAccountId = accountId;

        mExecutor.execute(() -> loginAsync (password, new LoginListener() {

            @Override
            public void onLoginSuccess() {

            }

            @Override
            public void onLoginFailed(String message) {

                PopupAlertManager.VectorAlert alert = new PopupAlertManager.VectorAlert("000",mContext.getString(R.string.login_error),message,R.drawable.alerts_and_states_error);

                alert.addButton(mContext.getString(R.string.ok),new Runnable(){
                    @Override
                    public void run() {

                        Activity act = alert.getWeakCurrentActivity().get();
                        try {
                            Intent intent = new Intent(act,Class.forName("info.guardianproject.keanuapp.ui.onboarding.OnboardingActivity"));
                            intent.putExtra("account",accountId);
                            intent.putExtra("provider",providerId);
                            act.startActivity(intent);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }


                    }
                },false);

                PopupAlertManager.INSTANCE.postVectorAlert(alert);
            }
        }));

    }

    public void loginAsync(long accountId, final String password, long providerId, LoginListener listener) {

        setState(LOGGING_IN, null);

        mProviderId = providerId;
        mAccountId = accountId;

        mExecutor.execute(() -> loginAsync (password, listener));

    }

    public void checkAccount(long accountId, final String password, long providerId, LoginListener listener) {
        setState(LOGGING_IN, null);

        mProviderId = providerId;
        mAccountId = accountId;

        mExecutor.execute(() -> checkAccount (password, listener));

    }

    private void initMatrix() {
        TrafficStats.setThreadStatsTag(THREAD_ID);

        Cursor cursor = mContext.getContentResolver().query(Imps.ProviderSettings.CONTENT_URI,
                new String[]{Imps.ProviderSettings.NAME, Imps.ProviderSettings.VALUE},
                Imps.ProviderSettings.PROVIDER + "=?",
                new String[]{Long.toString(mProviderId)}, null);

        if (cursor == null)
            return; //not going to work

        Imps.ProviderSettings.QueryMap providerSettings = new Imps.ProviderSettings.QueryMap(
                cursor, mContext.getContentResolver(), mProviderId, false, null);

        String server = providerSettings.getServer();
        if (TextUtils.isEmpty(server))
            server = providerSettings.getDomain();

        providerSettings.close();
        if (!cursor.isClosed())
            cursor.close();

        mConfig = new HomeServerConnectionConfig.Builder()
                .withHomeServerUri(Uri.parse(HTTPS_PREPEND + server))
                .withDNS(hostname -> {

                    if (mServers != null)
                        for (Server server1 : mServers) {
                            if (hostname.equalsIgnoreCase(server1.domain)) {
                                if (!TextUtils.isEmpty(server1.ip)) {
                                    byte[] bAddr = InetAddress.getByName(server1.ip).getAddress();
                                    InetAddress theAddr =
                                            InetAddress.getByAddress(hostname, bAddr);
                                    return Collections.singletonList(theAddr);
                                } else if (!TextUtils.isEmpty(server1.server)) {
                                    return Dns.SYSTEM.lookup(server1.server);
                                }
                            }
                        }

                    return Dns.SYSTEM.lookup(hostname);
                })
                .build();

        final boolean enableEncryption = true;

        mCredentials = new Credentials();
        mCredentials.userId = mUser.getAddress().getAddress();
        //noinspection deprecation
        mCredentials.homeServer = HTTPS_PREPEND + server;

        mConfig.setCredentials(mCredentials);
        mConfig.forceUsageOfTlsVersions();

        mStore = new KeanuMXFileStore(mConfig, enableEncryption, mContext);

        final int storeCommitInterval = 1000 * 60 * 5;
        final Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            public void run() {
                mStore.commit();
                handler.postDelayed(this, storeCommitInterval); //now is every 2 minutes
            }
        }, storeCommitInterval); //Every 120000 ms (2 minutes)

        mStore.addMXStoreListener(new IMXStoreListener() {
            @Override
            public void postProcess(String s) {
                debug("MXSTORE: postProcess: " + s);
            }

            @Override
            public void onStoreReady(String s) {
                debug("MXSTORE: onStoreReady: " + s);
            }

            @Override
            public void onStoreCorrupted(String s, String s1) {
                debug("MXSTORE: onStoreCorrupted: " + s + " " + s1);
            }

            @Override
            public void onStoreOOM(String s, String s1) {
                debug("MXSTORE: onStoreOOM: " + s + " " + s1);
            }

            @Override
            public void onReadReceiptsLoaded(String s) {
                debug("MXSTORE: onReadReceiptsLoaded: " + s);
            }
        });

        mStore.open();

        mDataHandler = new MXDataHandler(mStore, mCredentials);
        mDataHandler.addListener(mEventListener);

        mDataHandler.setLazyLoadingEnabled(false);

        mStore.setDataHandler(mDataHandler);

        mChatSessionManager.setDataHandler(mDataHandler);
        mChatGroupManager.setDataHandler(mDataHandler);

        mLoginRestClient = new LoginRestClient(mConfig);
    }

    private void checkAccount (final String password, LoginListener listener)
    {
        mExecutor.execute(() -> loginSync(password, false, listener));
    }

    private void loginAsync(final String password, final LoginListener listener) {

        mExecutor.execute(() -> loginSync(password, true, listener));

    }

    private void loginSync(String password, boolean enableEncryption, LoginListener listener) {

        String username = mUser.getAddress().getUser();
        setState(ImConnection.LOGGING_IN, null);

        if (password == null)
            password = Imps.Account.getPassword(mContext.getContentResolver(), mAccountId);

        final String initialToken = Preferences.getValue(mUser.getAddress().getUser() + ".sync");

        File fileCredsJson = new File("/" + username + "/creds.json");

        if (fileCredsJson.exists() & fileCredsJson.length() > 0) {
            mCredentials = new Credentials();
            try {
                String json = IOUtils.toString(new FileInputStream(fileCredsJson), Charset.defaultCharset());
                JsonObject jCreds = JsonParser.parseString(json).getAsJsonObject();

                mCredentials = new Credentials();
                mCredentials.accessToken = jCreds.get("access_token").getAsString();
                mCredentials.deviceId = jCreds.get("device_id").getAsString();
                //noinspection deprecation
                mCredentials.homeServer = jCreds.get("home_server").getAsString();
                if (jCreds.has("refresh_token"))
                    if (!jCreds.get("refresh_token").isJsonNull())
                        mCredentials.refreshToken = jCreds.get("refresh_token").getAsString();

                mCredentials.userId = jCreds.get("user_id").getAsString();

            } catch (Exception e) {
                debug("error reading from cred json", e);
                //noinspection ResultOfMethodCallIgnored
                fileCredsJson.delete();
                loginAsync(password, listener);
                return;
            }

            mConfig.setCredentials(mCredentials);

            initSession(enableEncryption, initialToken);

            if (listener != null)
                listener.onLoginSuccess();
        } else {
            mLoginRestClient.loginWithUser(username, password, mDeviceName, null, new SimpleApiCallback<Credentials>() {

                @Override
                public void onSuccess(Credentials credentials) {

                    setState(ImConnection.LOGGING_IN, null);

                    if (listener != null)
                        listener.onLoginSuccess();

                    mCredentials = credentials;

                    try {
                        File parent = fileCredsJson.getParentFile();
                        if (parent != null) //noinspection ResultOfMethodCallIgnored
                            parent.mkdirs();

                        String jCreds = mCredentials.toJson().toString();
                        info.guardianproject.iocipher.FileWriter writer = new info.guardianproject.iocipher.FileWriter(fileCredsJson);
                        writer.write(jCreds);
                        writer.flush();
                        writer.close();
                    } catch (IOException e) {
                        debug("error writing creds to file", e);
                    } catch (JSONException e) {
                        debug("error turning creds into json", e);
                    }

                    mConfig.setCredentials(mCredentials);

                    initSession(enableEncryption, initialToken);

                }


                @Override
                public void onNetworkError(Exception e) {
                    super.onNetworkError(e);

                    debug("loginWithUser: OnNetworkError", e);

                    setState(ImConnection.SUSPENDED, null);

                    if (listener != null)
                        listener.onLoginFailed(e.getMessage());

                    /**
                    mExecutor.execute(new Runnable() {
                        public void run() {
                            loginAsync(null, listener);
                        }
                    });**/
                }

                @Override
                public void onMatrixError(MatrixError e) {
                    super.onMatrixError(e);

                    if (listener != null)
                        listener.onLoginFailed(e.getMessage());

                    debug("loginWithUser: onMatrixError: " + e.mErrorBodyAsString);

                }

                @Override
                public void onUnexpectedError(Exception e) {
                    super.onUnexpectedError(e);

                    debug("loginWithUser: onUnexpectedError", e);
                    if (listener != null)
                        listener.onLoginFailed(e.getMessage());


                }
            });
        }
    }

    private void initSession(boolean enableEncryption, String initialToken) {


        mExecutor.execute(() -> {
            mSession = new MXSession.Builder(mConfig, mDataHandler, mContext.getApplicationContext())
                    .withFileEncryption(enableEncryption)
                    .build();

            mChatGroupManager.setSession(mSession);
            mChatSessionManager.setSession(mSession);


            setState(LOGGED_IN, null);
            mSession.setIsOnline(true);

            if (enableEncryption) {
                mSession.enableCrypto(true, new ApiCallback<Void>() {
                    @Override
                    public void onNetworkError(Exception e) {
                        debug("getCrypto().start.onNetworkError", e);

                    }

                    @Override
                    public void onMatrixError(MatrixError matrixError) {
                        debug("getCrypto().start.onMatrixError", matrixError);

                    }

                    @Override
                    public void onUnexpectedError(Exception e) {
                        debug("getCrypto().start.onUnexpectedError", e);
                    }

                    @Override
                    public void onSuccess(Void aVoid) {
                        IMXStore store = mSession.getDataHandler().getStore();
                        if (store != null) store.open();

                        debug("getCrypto().start.onSuccess");
                        mSession.startEventStream(initialToken);

                        mDataHandler.getCrypto().setWarnOnUnknownDevices(false);

                        mDataHandler.getMediaCache().clearShareDecryptedMediaCache();
                        mDataHandler.getMediaCache().clearTmpDecryptedMediaCache();

                        store = mDataHandler.getStore();
                        if (store != null) store.commit();
                    }
                });
            } else {
                debug("getCrypto().start.onSuccess");
                mSession.startEventStream(initialToken);

            }

        });
    }

    @Override
    public void reestablishSessionAsync(Map<String, String> sessionContext) {

        setState(ImConnection.LOGGED_IN, null);
        if (mSession != null)
            mSession.setIsOnline(true);
    }

    @Override
    public void logoutAsync() {

        logout(false);
    }

    @Override
    public synchronized void logout(boolean fullLogout) {

        setState(ImConnection.LOGGING_OUT, null);

        if (fullLogout) {
            if (mSession.isOnline() && mDataHandler.isAlive()) {
                mSession.stopEventStream();
                if (mSession.isAlive())
                    mSession.logout(mContext, new BasicApiCallback<>("loggout full"));
            }

            String username = mUser.getAddress().getUser();
            File fileCredsJson = new File("/" + username + "/creds.json");
            if (fileCredsJson.exists())
                //noinspection ResultOfMethodCallIgnored
                fileCredsJson.delete();


        } else if (mSession.isAlive()) {

            //we don't do a full logout here, since "logout" in Keanu parlance is just going offline
            mSession.stopEventStream();
            mSession.setIsOnline(false);
            setState(ImConnection.DISCONNECTED, null);

        } else {
            setState(ImConnection.DISCONNECTED, null);
        }
    }

    @Override
    public void suspend() {

        setState(ImConnection.SUSPENDED, null);

        if (mSession != null)
            mSession.setIsOnline(false);
    }

    @Override
    protected void setState(int state, ImErrorInfo error) {
        super.setState(state, error);

        if (mSession != null)
            mSession.setIsOnline(state != ImConnection.SUSPENDED);
    }

    @Override
    public Map<String, String> getSessionContext() {
        return mSessionContext;
    }

    @Override
    public ChatSessionManager getChatSessionManager() {
        return mChatSessionManager;
    }

    @Override
    public ContactListManager getContactListManager() {
        return mContactListManager;
    }

    @Override
    public ChatGroupManager getChatGroupManager() {
        return mChatGroupManager;
    }

    @Override
    public boolean isUsingTor() {
        return false;
    }

    @Override
    protected void doUpdateUserPresenceAsync(Presence presence) {

    }

    @Override
    public void sendHeartbeat(long heartbeatInterval) {

    }

    @Override
    public void setProxy(String type, String host, int port) {

    }

    @Override
    public void sendMessageRead(String roomId, String eventId) {

        Event event = mStore.getEvent(eventId, roomId);
        Room room = mStore.getRoom(roomId);
        room.sendReadReceipt(event, new BasicApiCallback<>("sendReadReceipt"));

    }

    @Override
    public void sendTypingStatus(String to, boolean isTyping) {

        mDataHandler.getRoom(to).sendTypingNotification(isTyping, 3000, new ApiCallback<Void>() {
            @Override
            public void onNetworkError(Exception e) {
                debug("sendTypingStatus:onNetworkError", e);
            }

            @Override
            public void onMatrixError(MatrixError e) {
                debug("sendTypingStatus:onMatrixError", e);

            }

            @Override
            public void onUnexpectedError(Exception e) {
                debug("sendTypingStatus:onUnexpectedError", e);

            }

            @Override
            public void onSuccess(Void aVoid) {

                debug("sendTypingStatus:onSuccess!");
            }
        });
    }

    @Override
    public List<String> getFingerprints(String address) {

        ArrayList<String> result = null;

        if (mDataHandler != null && mDataHandler.getCrypto() != null) {
            List<MXDeviceInfo> devices = mDataHandler.getCrypto().getUserDevices(address);

            //HashMap<String,List<MXDeviceInfo>> user = new HashMap<>();
            //user.put(address,devices);

            ArrayList<String> users = new ArrayList<>();
            users.add(address);

            mDataHandler.getCrypto().ensureOlmSessionsForUsers(users, new ApiCallback<MXUsersDevicesMap<MXOlmSessionResult>>() {
                @Override
                public void onNetworkError(Exception e) {

                }

                @Override
                public void onMatrixError(MatrixError matrixError) {

                }

                @Override
                public void onUnexpectedError(Exception e) {

                }

                @Override
                public void onSuccess(MXUsersDevicesMap<MXOlmSessionResult> mxOlmSessionResultMXUsersDevicesMap) {

                }
            });

            if (devices.size() > 0) {
                result = new ArrayList<>();

                for (MXDeviceInfo device : devices) {
                    String deviceInfo = device.displayName()
                            + '|' + device.deviceId
                            + '|' + device.fingerprint()
                            + '|' + device.isVerified();

                    result.add(deviceInfo);

                }
            }
        }

        return result;
    }

    @Override
    public void setDeviceVerified(String address, String device, boolean verified) {
        mDataHandler.getCrypto().setDeviceVerification(verified ? MXDeviceInfo.DEVICE_VERIFICATION_VERIFIED : MXDeviceInfo.DEVICE_VERIFICATION_UNVERIFIED,
                device, address, new BasicApiCallback<>("setDeviceVerified"));

        IMXStore store = mDataHandler.getStore();
        if (store != null) store.commit();
    }

    @Override
    public void broadcastMigrationIdentity(String newIdentity) {

    }

    @Override
    public void changeNickname(String nickname) {

        mDataHandler.getMyUser().updateDisplayName(nickname, new BasicApiCallback<>("changeNickname"));

        try {

            byte[] avatar = DatabaseUtils.getAvatarBytesFromAddress(mUser.getAddress().getAddress());
            if (avatar != null) {
                InputStream stream = new ByteArrayInputStream(avatar);
                String filename = nickname + "-avatar.jpg";
                String mimeType = "image/jpeg";

                mDataHandler.getMediaCache().uploadContent(stream, filename, mimeType, null,
                        new MXMediaUploadListener() {
                            @Override
                            public void onUploadStart(final String uploadId) {

                            }

                            @Override
                            public void onUploadCancel(final String uploadId) {

                            }

                            @Override
                            public void onUploadError(final String uploadId, final int serverResponseCode, final String serverErrorMessage) {

                            }

                            @Override
                            public void onUploadComplete(final String uploadId, final String contentUri) {
                                mDataHandler.getMyUser().updateAvatarUrl(contentUri, new BasicApiCallback<>("avatarloader"));
                            }
                        });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    @Override
    public String getDownloadUrl(String mxUrl) {
        return mSession.getContentManager().getDownloadableUrl(mxUrl, false);
    }

    @Override
    public void syncMessages(ChatSession session) {

        if (session != null)
        {
            Room room = mChatSessionManager.getRoom(session);
            if (room != null) {
                loadRoomState(room);

                /**
                 final List<ReceiptData> receipts = mDataHandler.getStore().getEventReceipts(room.getRoomId(), null, true, false);
                 for (ReceiptData data : receipts) {
                    session.onMessageReceipt(data.eventId, session.useEncryption());
                 }**/
            }
        }
    }

    private void redactMessage (String roomId, String redactId)
    {
        ChatSession session = mChatSessionManager.getSession(roomId);

        if (session != null
            && session.getMessageListener() != null)
        session.getMessageListener().onMessageRedacted(redactId);

    }

    @Override
    public void refreshMessage(ChatSession session,String eventId) {
        if (session != null)
        {
            Room room = mChatSessionManager.getRoom(session);
            if (room != null) {
                mSession.getEventsApiClient().getEventFromEventId(eventId, new ApiCallback<Event>() {
                    @Override
                    public void onNetworkError(Exception e) {

                    }

                    @Override
                    public void onMatrixError(MatrixError matrixError) {

                    }

                    @Override
                    public void onUnexpectedError(Exception e) {

                    }

                    @Override
                    public void onSuccess(Event event) {

                        requestSessionKeyForMessage(event, room);

                        if (!event.isUndelivered())
                            session.onMessageReceipt(eventId, session.useEncryption());
                    }
                });


            }
        }

    }

    @Override
    public void checkReceipt(ChatSession session,String eventId) {
        if (session != null)
        {
            Room room = mChatSessionManager.getRoom(session);
            if (room != null) {
                mSession.getEventsApiClient().getEventFromEventId(eventId, new ApiCallback<Event>() {
                    @Override
                    public void onNetworkError(Exception e) {

                    }

                    @Override
                    public void onMatrixError(MatrixError matrixError) {

                    }

                    @Override
                    public void onUnexpectedError(Exception e) {

                    }

                    @Override
                    public void onSuccess(Event event) {

                         if (!event.isUndelivered())
                            session.onMessageReceipt(eventId, session.useEncryption());
                    }
                });


            }
        }

    }

    @Override
    public void uploadContent (InputStream is, String contentTitle, String mimeType, final ConnectionListener listener) {
        mDataHandler.getMediaCache().uploadContent(is, contentTitle, mimeType, null,
                new MXMediaUploadListener() {
                    @Override
                    public void onUploadStart(final String uploadId) {

                    }

                    @Override
                    public void onUploadCancel(final String uploadId) {

                    }

                    @Override
                    public void onUploadError(final String uploadId, final int serverResponseCode, final String serverErrorMessage) {

                    }

                    @Override
                    public void onUploadComplete(final String uploadId, final String contentUri) {

                        String downloadUrl = mSession.getContentManager().getDownloadableUrl(contentUri, false);

                        if (listener != null)
                            listener.uploadComplete(downloadUrl);

                    }
                });
    }

    private void loadStateAsync ()
    {

        mExecutor.execute(() -> {

            Collection<Room> rooms = mStore.getRooms();

            for (Room room : rooms)
                loadRoomState(room);

            mContactListManager.loadContactListsAsync();

        });

    }

    private void loadRoomState (Room room)
    {
        if (room.isMember()) {
            addRoomContact(room, null);
        }

        /**
        String roomReadMarkerId = room.getReadMarkerEventId();
        room.getTimeline().addEventTimelineListener(new EventTimeline.Listener() {
            @Override
            public void onEvent(Event event, EventTimeline.Direction direction, RoomState roomState) {
                mEventListener.onLiveEvent(event, roomState);
            }
        });**/

    }

    public void requestSessionKeyForMessage (Event eventEnc, Room room)
    {
        if (eventEnc != null) {

            mSession.getCrypto().reRequestRoomKeyForEvent(eventEnc);

            try {
                MXEventDecryptionResult result = mSession.getCrypto().decryptEvent(eventEnc, room.getTimeline().getTimelineId());
                eventEnc.setClearData(result);
                lbqMessages.add(eventEnc);

            } catch (MXDecryptionException e) {
                e.printStackTrace();
            }
        }

    }

    public void requestSessionKeyForMessage (String eventId, String roomId)
    {
        Room room = mSession.getDataHandler().getRoom(roomId);
        Event eventEnc = mSession.getDataHandler().getStore().getEvent(eventId, roomId);

        if (eventEnc != null) {
            mSession.getCrypto().reRequestRoomKeyForEvent(eventEnc);

            try {
                MXEventDecryptionResult result = mSession.getCrypto().decryptEvent(eventEnc, room.getTimeline().getTimelineId());

            } catch (MXDecryptionException e) {
                e.printStackTrace();
            }
        }
    }

    protected ChatGroup addRoomContact(final Room room, String subject) {
        debug("addRoomContact: " + room.getRoomId() + " - " + room.getRoomDisplayName(mContext) + " - " + room.getNumberOfMembers());

        MatrixAddress mAddr = new MatrixAddress(room.getRoomId());

        final ChatGroup group = mChatGroupManager.getChatGroup(mAddr);

        if (TextUtils.isEmpty(subject))
            subject = room.getRoomDisplayName(mContext);

        group.setName(subject);

        ChatSessionAdapter csa = mChatSessionManager.getChatSessionAdapter(room.getRoomId());
        if (csa == null) {

            mChatSessionManager.createChatSession(group, true);
            csa = mChatSessionManager.getChatSessionAdapter(room.getRoomId());

        }

        if (csa != null && !subject.equals(mContext.getString(R.string.default_group_title))) {
            try {
                csa.setGroupChatSubject(subject,false);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        updateRoom(room, group);

        checkRoomEncryption(room);

        String downloadUrl = mSession.getContentManager().getDownloadableThumbnailUrl(room.getAvatarUrl(),
                DEFAULT_AVATAR_WIDTH, DEFAULT_AVATAR_HEIGHT, "scale");

        if (!TextUtils.isEmpty(downloadUrl)) {
            if (!hasAvatar(room.getRoomId(), downloadUrl)) {
                downloadAvatar(room.getRoomId(), downloadUrl);
            }
        }

        group.setLoaded();

        return group;
    }

    protected void updateRoom(Room room) {
        updateRoom(room, null);
    }

    protected void updateRoom(Room room, ChatGroup group) {
        if (room.isInvited() || room.isMember()) {

            if (group == null)
                group = mChatGroupManager.getChatGroup(new MatrixAddress(room.getRoomId()));

            group.setName(room.getRoomDisplayName(mContext));

            ChatSessionAdapter csa = mChatSessionManager.getChatSessionAdapter(room.getRoomId());
            if (csa != null)
                csa.presenceChanged(Presence.AVAILABLE);

            if (group.shouldUpdate())
                updateGroupMembersAsync(room, group);

        }
    }

    protected void updateGroupMembersAsync(final Room room, final ChatGroup group) {
        group.setLastUpdated();

        room.getMembersAsync(new ApiCallback<List<RoomMember>>() {
            @Override
            public void onNetworkError(Exception e) {
                debug("Network error syncing active members", e);


            }

            @Override
            public void onMatrixError(MatrixError matrixError) {
                debug("Matrix error syncing active members", matrixError);


            }

            @Override
            public void onUnexpectedError(Exception e) {

                debug("Error syncing active members", e);


            }

            @Override
            public void onSuccess(final List<RoomMember> roomMembers) {

                GroupMemberLoader gLoader = new GroupMemberLoader(room, group, roomMembers);

                mExecutor.execute(gLoader);

            }
        });
    }

    class GroupMemberLoader implements Runnable {

        Room room;
        ChatGroup group;
        List<RoomMember> members;

        public GroupMemberLoader(Room room, ChatGroup group, List<RoomMember> members) {
            this.room = room;
            this.group = group;
            this.members = members;
        }

        public void run() {


            ArrayList<String> userList = new ArrayList<>();

            final PowerLevels powerLevels = room.getState().getPowerLevels();

            group.beginMemberUpdates();

            for (RoomMember member : members) {
                debug("RoomMember: " + room.getRoomId() + ": " + member.getName() + " (" + member.getUserId() + ")");

                userList.add(member.getUserId());
                Contact contact = mContactListManager.getContact(member.getUserId());

                if (contact == null) {
                    if (member.getName() != null)
                        contact = new Contact(new MatrixAddress(member.getUserId()), member.getName(), Imps.Contacts.TYPE_NORMAL);
                    else
                        contact = new Contact(new MatrixAddress(member.getUserId()));
                }

                //this is a one-to-one chat (2 people in a room), save the contact as a local one
                if (members.size() == 2) {
                    if (!member.getUserId().equals(mDataHandler.getUserId())) {
                        mContactListManager.saveContact(contact);
                    }
                }

                if (group.getMember(member.getUserId()) == null)
                    group.notifyMemberJoined(member.getUserId(), contact);

                if (powerLevels != null) {
                    if (powerLevels.getUserPowerLevel(member.getUserId()) >= powerLevels.ban)
                        group.notifyMemberRoleUpdate(contact, "admin", "owner");
                    else
                        group.notifyMemberRoleUpdate(contact, "member", "member");
                }

                if (member.membership.equals("invite")) {
                    group.notifyMemberRoleUpdate(contact, "member", "invited");
                }


                if (members.size() < 40) {
                    User aUser = mDataHandler.getUser(member.getUserId());

                    if (aUser != null) {
                        String avatarUrl = aUser.getAvatarUrl();
                        String downloadUrl = mSession.getContentManager().getDownloadableThumbnailUrl(avatarUrl,
                                DEFAULT_AVATAR_WIDTH, DEFAULT_AVATAR_HEIGHT, "scale");

                        if (!TextUtils.isEmpty(downloadUrl)) {
                            if (!hasAvatar(member.getUserId(), downloadUrl)) {
                                downloadAvatar(member.getUserId(), downloadUrl);
                            }
                        }
                    }
                }
            }

            group.endMemberUpdates();

            if (room.isEncrypted())
                if (mDataHandler != null && mDataHandler.getCrypto() != null && (!userList.isEmpty()))
                mDataHandler.getCrypto().ensureOlmSessionsForUsers(userList, new BasicApiCallback<>("ensureOlmSessions"));
        }
    }

    protected void checkRoomEncryption(Room room) {

        ChatSessionAdapter csa = mChatSessionManager.getChatSessionAdapter(room.getRoomId());
        if (csa != null) {
            if (mDataHandler != null && mDataHandler.getCrypto() != null) {
                boolean isEncrypted = room.isEncrypted();

                if (!csa.getUseEncryption() == isEncrypted)
                    csa.updateEncryptionState(isEncrypted);


            }
        }
    }

    protected void debug(String msg, MatrixError error) {
        if (Debug.DEBUG_ENABLED)
            Log.w(TAG, msg + ": " + error.errcode + "=" + error.getMessage());

    }

    protected void debug(String msg) {
        if (Debug.DEBUG_ENABLED)
            Log.d(TAG, msg);

    }

    protected void debug(String msg, Exception e) {
        if (Debug.DEBUG_ENABLED)
            Log.e(TAG, msg, e);
    }

    IMXEventListener mEventListener = new IMXEventListener() {
        @Override
        public void onStoreReady() {

            debug("onStoreReady!");

        }

        @Override
        public void onPresenceUpdate(Event event, User user) {

            debug ("PRESENCE: from=" + event.getSender() + ": " + event.getContent());
            mExecutor.execute(() -> handlePresence(event));

        }

        @Override
        public void onAccountInfoUpdate(MyUser myUser) {
            debug("onAccountInfoUpdate: " + myUser);

        }

        @Override
        public void onIgnoredUsersListUpdate() {

        }

        @Override
        public void onDirectMessageChatRoomsListUpdate() {
            debug("onDirectMessageChatRoomsListUpdate");

        }

        @Override
        public void onLiveEvent(final Event event, RoomState roomStateInfo) {

            debug("onLiveEvent:type=" + event.getType());

            if (event.getType().equals(Event.EVENT_TYPE_MESSAGE) || event.getType().equals(EVENT_TYPE_REACTION))  // TODO - Why is this not a constant in the SDK, does it still rely on some server side hack that the IOS code mentions?
            {
                //handleIncomingMessage(event);
                lbqMessages.add(event);

            }
            else if (event.getType().equals(Event.EVENT_TYPE_REDACTION))
            {
                Event eventRedaction = event.getClearEvent();
                redactMessage(event.roomId, event.redacts);
            }
            else if (event.getType().equals(Event.EVENT_TYPE_STATE_ROOM_MEMBER))
            {
                mExecutor.execute(() -> handleRoomMemberEvent(event));

            }
            else if (event.getType().equals(Event.EVENT_TYPE_PRESENCE))
            {
                debug ("PRESENCE: from=" + event.getSender() + ": " + event.getContent());
                mExecutor.execute(() -> handlePresence(event));

            } else if (event.getType().equals(Event.EVENT_TYPE_READ_MARKER)) {
                debug("READ MARKER: from=" + event.getSender() + ": " + event.getContent());

                if (event.getContent().getAsJsonObject() != null) {
                    for (String eventId : event.getContent().getAsJsonObject().keySet()) {
                        if (event.getContent().getAsJsonObject().getAsJsonObject(eventId).has("m.read")) {
                            String userId = event.getContent().getAsJsonObject().getAsJsonObject(eventId).getAsJsonObject("m.read").keySet().iterator().next();

                            ChatSession session = mChatSessionManager.getSession(event.roomId);
                            if (session != null) {
                                if (!userId.equals(mSession.getMyUserId())) {
                                    session.onMessageReceipt(eventId, session.useEncryption());
                                } else {
                                    mChatSessionManager.getChatSessionAdapter(event.roomId).markAsRead();
                                }
                            }
                            if (!userId.equals(mSession.getMyUserId())) {
                                if (session != null)
                                    session.onMessageReceipt(eventId, session.useEncryption());
                            } else {
                                mChatSessionManager.getChatSessionAdapter(event.roomId).markAsRead();
                            }
                        }
                    }
                }
            } else if (event.getType().equals(Event.EVENT_TYPE_RECEIPT)) {
                debug("RECEIPT: from=" + event.getSender() + ": " + event.getContent());

                /*
                 * {
                 * "age" : null,
                 * "content": {
                 * "$155369867390511tYryz:matrix.org": {"m.read":{"@earthmouse:matrix.org":{"ts":1553698822615}}},
                 * },
                 * "eventId": "null",
                 * "originServerTs": 0,
                 * "roomId": "!gvfFCZAYqQKjvlnWcn:matrix.org",
                 * "type": "m.receipt",
                 * "userId": "null",
                 * "sender": "null",
                 * }
                 */

                if (event.getContent().getAsJsonObject() != null) {
                    for (String eventId : event.getContent().getAsJsonObject().keySet()) {
                        if (event.getContent().getAsJsonObject().getAsJsonObject(eventId).has("m.read")) {
                            String userId = event.getContent().getAsJsonObject().getAsJsonObject(eventId).getAsJsonObject("m.read").keySet().iterator().next();

                            ChatSession session = mChatSessionManager.getSession(event.roomId);
                            if (session != null) {
                                if (!userId.equals(mSession.getMyUserId())) {
                                    session.onMessageReceipt(eventId, session.useEncryption());
                                } else {
                                    mChatSessionManager.getChatSessionAdapter(event.roomId).markAsRead();
                                }
                            }
                        }

                    }
                }

            }
            else if (event.getType().equals(Event.EVENT_TYPE_TYPING))
            {
                debug ("TYPING: from=" + event.getSender() + ": " + event.getContent());
                mExecutor.execute(() -> handleTyping(event));
            }
            else if (event.getType().equals(Event.EVENT_TYPE_FORWARDED_ROOM_KEY))
            {
                debug ("EVENT_TYPE_FORWARDED_ROOM_KEY: from=" + event.getSender() + ": " + event.getContent());

            }
            else if (event.getType().equals(Event.EVENT_TYPE_STICKER))
            {
                debug ("STICKER: from=" + event.getSender() + ": " + event.getContent());
                mExecutor.execute(() -> { handleIncomingSticker(event);});

            }
            else if (event.getType().equals(Event.EVENT_TYPE_STATE_ROOM_NAME)) {
                mExecutor.execute(() -> {
                    ChatSessionAdapter csa = mChatSessionManager.getChatSessionAdapter(event.roomId);
                    if (csa != null) {
                        String newName = event.getContent().getAsJsonObject().get("name").getAsString();
                        try {
                            csa.setGroupChatSubject(newName,false);
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }

                });
            }
            else if (event.getType().equals(Event.EVENT_TYPE_STATE_ROOM_AVATAR)) {
                mExecutor.execute(() -> {
                    Room room = mStore.getRoom(event.roomId);
                    String downloadUrl = mSession.getContentManager().getDownloadableThumbnailUrl(room.getAvatarUrl(),
                            DEFAULT_AVATAR_WIDTH, DEFAULT_AVATAR_HEIGHT, "scale");

                    if (!TextUtils.isEmpty(downloadUrl)) {
                        if (!hasAvatar(room.getRoomId(), downloadUrl)) {
                            downloadAvatar(room.getRoomId(), downloadUrl);
                        }
                    }
                });
            } else if (!TextUtils.isEmpty(event.type)) {

                if (mSession != null && mSession.isCryptoEnabled()) {
                    if (event.type.equals(EVENT_TYPE_MESSAGE_ENCRYPTED)) {
                        if (mSession.getCrypto() != null) {
                            mSession.getCrypto().reRequestRoomKeyForEvent(event);
                            ArrayList<String> users = new ArrayList<>();
                            users.add(event.sender);
                            mSession.getCrypto().ensureOlmSessionsForUsers(users,new BasicApiCallback("ensureOlmSessionsForUsers"));
                            Room room = mSession.getDataHandler().getRoom(event.roomId);
                            if (room != null)
                                requestSessionKeyForMessage(event, room);


                        }



                    }
                }
            }

            Preferences.setValue(mUser.getAddress().getUser() + ".sync", mSession.getCurrentSyncToken());

        }

        @Override
        public void onLiveEventsChunkProcessed(String s, String s1) {
            debug("onLiveEventsChunkProcessed: " + s + ":" + s1);
        }

        @Override
        public void onBingEvent(Event event, RoomState roomState, BingRule bingRule) {
            debug("bing: " + event.toString());

            Room room = mStore.getRoom(roomState.roomId);
            if (room.isInvited()) {
                handleRoomInvite(room, event.sender);
            }

            if (EVENT_TYPE_MESSAGE_ENCRYPTED.equals(event.type)) {
                MXCrypto crypto = mSession.getCrypto();
                if (crypto != null) crypto.reRequestRoomKeyForEvent(event);
            }
        }

        @Override
        public void onEventSentStateUpdated(Event event) {
            debug("onEventSentStateUpdated: " + event);

        }

        @Override
        public void onEventSent(Event event, String s) {
            debug("onEventSent: " + event + " s:" + s);
        }

        @Override
        public void onEventDecrypted(String s, String s1) {
            debug ("onEventDecrypted: " + s + ":" + s1);

            mSession.getEventsApiClient().getEventFromEventId(s, new ApiCallback<Event>() {
                @Override
                public void onNetworkError(Exception e) {

                }

                @Override
                public void onMatrixError(MatrixError matrixError) {

                }

                @Override
                public void onUnexpectedError(Exception e) {

                }

                @Override
                public void onSuccess(Event event) {
                        onLiveEvent(event, null);
                }
            });
        }


        @Override
        public void onBingRulesUpdate() {
            debug("onBingRulesUpdate");
        }

        @Override
        public void onInitialSyncComplete(String s) {

            debug("onInitialSyncComplete: " + s);
            if (null != mSession.getCrypto()) {

                if (mKeyRequestHandler == null)
                    mKeyRequestHandler = new KeyRequestHandler(mSession);

                mSession.getCrypto().addRoomKeysRequestListener(new RoomKeysRequestListener() {
                    @Override
                    public void onRoomKeyRequest(@NotNull IncomingRoomKeyRequest request) {

                        debug("onRoomKeyRequest: " + request);
//                        downloadKeys(request.mUserId,request.mDeviceId);
                        mKeyRequestHandler.handleKeyRequest(request, mContext);

                    }

                    @Override
                    public void onRoomKeyRequestCancellation(@NotNull IncomingRoomKeyRequestCancellation request) {
                        //KeyRequestHandler.getSharedInstance().handleKeyRequestCancellation(request);
                        debug("onRoomKeyRequestCancellation: " + request);
                        mKeyRequestHandler.handleKeyRequestCancellation(request);
                    }
                });
            }

            loadStateAsync();
        }

        @Override
        public void onSyncError(MatrixError matrixError) {
            debug("onSyncError", matrixError);
        }

        @Override
        public void onCryptoSyncComplete() {
            debug("onCryptoSyncComplete");
        }

        @Override
        public void onNewRoom(String s) {
            debug("onNewRoom: " + s);

            final Room room = mStore.getRoom(s);
            if (room != null) {
                handleRoomInvite(room, s);
            }
        }

        @Override
        public void onJoinRoom(String s) {
            debug("onJoinRoom: " + s);

            Room room = mStore.getRoom(s);
            if (room != null) {

                mExecutor.execute(() -> addRoomContact(room, null));
            }
        }

        @Override
        public void onRoomFlush(String s) {
            debug("onRoomFlush: " + s);

            Room room = mDataHandler.getRoom(s);
            if (room != null)
            {
                mExecutor.execute(() -> updateRoom(room));
            }
        }

        @Override
        public void onRoomInternalUpdate(String s) {
            debug("onRoomInternalUpdate: " + s);
            Room room = mDataHandler.getRoom(s);
            if (room != null)
            {
                mExecutor.execute(() -> updateRoom(room));

            }
        }

        @Override
        public void onNotificationCountUpdate(String s) {
            debug("onNotificationCountUpdate: " + s);
        }

        @Override
        public void onLeaveRoom(String s) {
            debug("onLeaveRoom: " + s);
        }

        @Override
        public void onRoomKick(String s) {
            //   Room room = mDataHandler.getRoom(s);
            // if (room != null)
            //    updateGroup(room);
        }

        @Override
        public void onReceiptEvent(final String roomId, final List<String> list) {

            mExecutor.execute(new Runnable (){

                public void run () {
                    debug("onReceiptEvent: " + roomId);

                    Room room = mStore.getRoom(roomId);
                    ChatSession session = mChatSessionManager.getSession(roomId);


                    if (session != null) {
                        for (String userId : list) {
                            //userId who got the room receipt
                            ReceiptData data = mStore.getReceipt(roomId, userId);
                            session.onMessageReadMarker(data.eventId, session.useEncryption());

                            if (userId.equals(mSession.getMyUserId())) {
                                mChatSessionManager.getChatSessionAdapter(roomId).markAsRead();
                            }
                        }
                    }
                }
            });
        }

        @Override
        public void onRoomTagEvent(String s) {
            debug("onRoomTagEvent: " + s);
        }

        @Override
        public void onTaggedEventsEvent(String s) {
            debug("onTaggedEventsEvent: " + s);
        }

        @Override
        public void onReadMarkerEvent(String s) {
            debug("onReadMarkerEvent: " + s);
        }

        @Override
        public void onToDeviceEvent(Event event) {
            debug("onToDeviceEvent: " + event);
        }

        @Override
        public void onNewGroupInvitation(final String s) {
            Room room = mStore.getRoom(s);

            if (room.isInvited()) {
                onNewRoom(room.getRoomId());
            }

        }

        @Override
        public void onJoinGroup(String s) {

            mExecutor.execute(new Runnable ()
            {
                public void run ()
                {
                    debug ("onNewGroupInvitation: " + s);
                    Room room = mStore.getRoom(s);
                    if (room != null)
                        addRoomContact(room, null);
                }
            });
        }

        @Override
        public void onLeaveGroup(String s) {
            debug("onLeaveGroup: " + s);
        }

        @Override
        public void onGroupProfileUpdate(String s) {
            Room room = mDataHandler.getRoom(s);
            if (room != null)
            {
                mExecutor.execute(() -> updateRoom(room));
            }
        }

        @Override
        public void onGroupRoomsListUpdate(String s) {
            debug("onGroupRoomsListUpdate: " + s);
        }

        @Override
        public void onGroupUsersListUpdate(String s) {
            debug("onGroupUsersListUpdate: " + s);

            Room room = mDataHandler.getRoom(s);
            if (room != null)
            {
                mExecutor.execute(() -> updateRoom(room));
            }
        }

        @Override
        public void onGroupInvitedUsersListUpdate(String s) {
            Room room = mDataHandler.getRoom(s);
            if (room != null)
            {
                mExecutor.execute(() -> updateRoom(room));
            }
        }

        @Override
        public void onAccountDataUpdated(AccountDataElement accountDataElement) {
            debug("onAccountDataUpdated: " + accountDataElement);
        }
    };

    private void handlePresence(Event event) {
        User user = mStore.getUser(event.getSender());

        //Not me!
        if (!user.user_id.equals(mDataHandler.getUserId())) {
            Contact contact = mContactListManager.getContact(user.user_id);

            if (contact != null) {
                boolean currentlyActive = false;
//                int lastActiveAgo = -1;

                JsonObject content = event.getContentAsJsonObject();

                if (content != null && content.has("currently_active")
                        && (!(content.get("currently_active") instanceof JsonNull))
                )
                    currentlyActive = content.get("currently_active").getAsBoolean();

//                if (event.getContentAsJsonObject().has("last_active_ago")
//                && (!(event.getContentAsJsonObject().get("last_active_ago") instanceof JsonNull))
//                )
//                    lastActiveAgo = event.getContentAsJsonObject().get("last_active_ago").getAsInt();

                contact.setPresence(new Presence(currentlyActive ? Presence.AVAILABLE : Presence.OFFLINE));

                Contact[] contacts = {contact};
                mContactListManager.notifyContactsPresenceUpdated(contacts);

                if (!TextUtils.isEmpty(event.roomId)) {
                    ChatGroup group = mChatGroupManager.getChatGroup(event.roomId);
                    if (group != null && group.getMember(event.getSender()) == null) {
                        group.notifyMemberJoined(contact);
                    }
                }
            }

            String downloadUrl = mSession.getContentManager().getDownloadableThumbnailUrl(user.getAvatarUrl(),
                    DEFAULT_AVATAR_WIDTH, DEFAULT_AVATAR_HEIGHT, "scale");

            if (!TextUtils.isEmpty(downloadUrl)) {
                if (!hasAvatar(user.user_id, downloadUrl)) {
                    downloadAvatar(user.user_id, downloadUrl);
                }
            }
        }
    }

    private void handleRoomMemberEvent(Event event) {
        /*
         * {
         *     "content": {
         *         "avatar_url": "mxc://example.org/SEsfnsuifSDFSSEF",
         *         "displayname": "Alice Margatroid",
         *         "membership": "join"
         *     },
         *     "event_id": "$143273582443PhrSn:example.org",
         *     "origin_server_ts": 1432735824653,
         *     "room_id": "!jEsUZKDJdhlrceRyVU:example.org",
         *     "sender": "@example:example.org",
         *     "state_key": "@alice:example.org",
         *     "type": "m.room.member",
         *     "unsigned": {
         *         "age": 1234
         *     }
         * }
         */

        JsonObject content = event.getContentAsJsonObject();

        String membership = null;
        if (content != null && content.has("membership"))
            membership = content.get("membership").getAsString();

        String member = event.stateKey;

        if ((!TextUtils.isEmpty(membership)) && (!TextUtils.isEmpty(member))) {
            if (membership.equals("join")) {
                String displayname = null;
                if (content.has("displayname") && content.get("displayname") != null
                        && (!(content.get("displayname") instanceof JsonNull)))
                    displayname = content.get("displayname").getAsString();

//                String avatar_url = null;
//                if (content.has("avatar_url") && content.get("avatar_url") != null
//                        && (!(content.get("avatar_url") instanceof JsonNull)))
//                    avatar_url = content.get("avatar_url").getAsString();

                Contact contact = mContactListManager.getContact(member);

                if (contact == null)
                    contact = new Contact(new BaseAddress(member));

                contact.setName(displayname);
                contact.setPresence(new Presence(Presence.AVAILABLE));
                Contact[] contacts = {contact};
                mContactListManager.notifyContactsPresenceUpdated(contacts);

                ChatGroup group = mChatGroupManager.getChatGroup(event.roomId);
                if (group != null && group.getMember(event.getSender()) == null) {
                    group.notifyMemberJoined(contact);
                }
            }
        }
    }

    private void handleTyping(Event event) {
        JsonObject content = event.getContentAsJsonObject();

        if (content != null && content.has("user_ids")) {
            JsonArray userIds = content.get("user_ids").getAsJsonArray();

            for (JsonElement element : userIds) {
                String userId = element.getAsString();
                if (!userId.equals(mSession.getMyUserId())) {
                    if (mChatSessionManager != null && mChatSessionManager.getAdapter() != null) {
                        IChatSession csa = mChatSessionManager.getAdapter().getChatSession(event.roomId);
                        if (csa != null) {
                            try {
                                csa.setContactTyping(new Contact(new MatrixAddress(userId)), true);
                            } catch (RemoteException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        }
    }

    private void handleIncomingSticker(Event event) {
        /*
         *
         * {
         *   "age" : null,
         *   "content": {
         *     "body": "A hastily-rendered stick figure stands with arms in the air beneath three blue-and-white juggling balls apparently in motion. We cannot tell whether the figure is juggling competently or has simply thrown all three balls into the air and is awaiting the inevitable. The figure's mouth is formed into an enigmatic 'o'.",
         *     "info": {"h":200,"mimetype":"image/png","size":30170,"thumbnail_info":{"h":200,"mimetype":"image/png","size":30170,"w":88},"thumbnail_url":"mxc://matrix.org/mQEotjwsEKeZivqIfZjxNfgC","w":88},
         *     "url": "mxc://matrix.org/mQEotjwsEKeZivqIfZjxNfgC",
         *   },
         *   "eventId": "$154896906639558KPSVT:matrix.org",
         *   "originServerTs": 1548969066195,
         *   "roomId": "!LprBecrICHRvlyHpsO:matrix.org",
         *   "type": "m.sticker",
         *   "userId": "null",
         *   "sender": "@n8fr8:matrix.org",
         * }
         *
         *  Sent state : SENT
         */
        if (!TextUtils.isEmpty(event.getSender())) {

            debug("MESSAGE: room=" + event.roomId + " from=" + event.getSender() + " event=" + event.toString());

            String stickerAlt = null;
            String stickerUrl = null;
            String stickerType = "image/png";

            if (event.getContent().getAsJsonObject().has("body"))
                stickerAlt = event.getContent().getAsJsonObject().get("body").getAsString();

            if (event.getContent().getAsJsonObject().has("url"))
                stickerUrl = event.getContent().getAsJsonObject().get("url").getAsString();
            else if (event.getContent().getAsJsonObject().has("sticker_pack")) {
                stickerUrl = "asset://stickers/" + event.getContent().getAsJsonObject().get("sticker_pack").getAsString()
                        + "/" + event.getContent().getAsJsonObject().get("sticker_name").getAsString() + ".png";

            }

            if (!TextUtils.isEmpty(stickerUrl)) {

                Room room = mStore.getRoom(event.roomId);

                if (room != null && room.isMember()) {

                    MatrixAddress addrSender = new MatrixAddress(event.sender);
                    Uri uriSticker = Uri.parse(stickerUrl);

                    String localFolder = addrSender.getAddress();
                    String localFileName = new Date().getTime() + '.' + uriSticker.getPath();
                    String downloadableUrl = mSession.getContentManager().getDownloadableUrl(stickerUrl, false);

                    if ("mxc".equals(uriSticker.getScheme())) {
                        try {
                            MatrixDownloader dl = new MatrixDownloader();
                            info.guardianproject.iocipher.File fileDownload = dl.openSecureStorageFile(localFolder, localFileName);
                            OutputStream storageStream = new info.guardianproject.iocipher.FileOutputStream(fileDownload);
                            dl.get(downloadableUrl, storageStream);
                            stickerAlt = SecureMediaStore.vfsUri(fileDownload.getAbsolutePath()).toString();

                        } catch (Exception e) {
                            debug("Error downloading file: " + downloadableUrl, e);
                        }
                    } else //noinspection StatementWithEmptyBody
                        if ("asset".equals(uriSticker.getScheme())) {
                            //what do we do?
                        }

                    if (!TextUtils.isEmpty(stickerAlt)) {
                        ChatSession session = mChatSessionManager.getSession(event.roomId);

                        if (session == null) {
                            ImEntity participant = mChatGroupManager.getChatGroup(new MatrixAddress(event.roomId));
                            session = mChatSessionManager.createChatSession(participant, false);
                            if (session == null)
                                return;
                        }

                        Message message = new Message(stickerAlt);
                        message.setID(event.eventId);
                        message.setFrom(addrSender);
                        message.setDateTime(new Date(event.originServerTs));//use "age"?
                        message.setContentType(stickerType);

                        if (mDataHandler.getRoom(event.roomId).isEncrypted()) {
                            message.setType(Imps.MessageType.INCOMING_ENCRYPTED);
                        } else
                            message.setType(Imps.MessageType.INCOMING);

                        session.onReceiveMessage(message, true);

                        IChatSession csa = mChatSessionManager.getAdapter().getChatSession(event.roomId);
                        if (csa != null) {
                            try {
                                csa.setContactTyping(new Contact(addrSender), false);
                            } catch (RemoteException e) {
                                e.printStackTrace();
                            }
                        }


                    }
                }
            }


        }

    }

    LinkedBlockingQueue<Event> lbqMessages = new LinkedBlockingQueue<>();
    Timer mTimerMessageLoader;

    private void initMessageLoader () {
        mTimerMessageLoader = new Timer();

        mTimerMessageLoader.schedule(new TimerTask() {

            public void run() {

                while (lbqMessages.peek() != null) {
                    Event event = lbqMessages.poll();
                    handleIncomingMessage(event);
                }

            }

        }, 0, 2000);
    }

    private void handleIncomingMessage (Event event)
    {

        if (!TextUtils.isEmpty(event.getSender())) {

            debug("MESSAGE: room=" + event.roomId + " from=" + event.getSender() + " event=" + event.toString());

            Room room = mStore.getRoom(event.roomId);

            if (room != null && room.isMember()) {

                ChatSession session = mChatSessionManager.getSession(event.roomId);
                if (session == null) {
                    ImEntity participant = mChatGroupManager.getChatGroup(new MatrixAddress(event.roomId));
                    session = mChatSessionManager.createChatSession(participant, false);
                    if (session == null)
                        return;
                }

                if (room == null || TextUtils.isEmpty(event.eventId))
                    return;

                ChatSessionAdapter csa = mChatSessionManager.getChatSessionAdapter(event.roomId);

                String eventId = event.eventId;
                String messageBody = null;
                Pair<String, String> replyToInfo = null;

                JsonObject mObj = event.getContent().getAsJsonObject();


                if (mObj.has("body"))
                    messageBody = mObj.get("body").getAsString();

                if (mObj.has("m.new_content")) {

                    //  "content": {
                    //    "m.new_content": {"msgtype":"m.text","body":"yes one two threeadsasdasdsadsadadasdasdasdasasdasdsadasd"},
                    //    "msgtype": "m.text",
                    //    "body": " * yes one two threeadsasdasdsadsadadasdasdasdasasdasdsadasd",
                    //    "m.relates_to": {"event_id":"$1599077041785134psDVO:matrix.org","rel_type":"m.replace"},
                    //  },

                    messageBody = mObj.getAsJsonObject("m.new_content").get("body").getAsString() + " " + mContext.getString(R.string.edited);
                    eventId = mObj.getAsJsonObject("m.relates_to").get("event_id").getAsString();


                } else {
                    replyToInfo = getReplyToFromEvent(event);
                }

                boolean isQuickReaction = event.getType().equals(EVENT_TYPE_REACTION);
                if (isQuickReaction && replyToInfo != null) {
                    messageBody = replyToInfo.second;
                }

                if (TextUtils.isEmpty(messageBody)) {
                    debug("WARN: MESSAGE HAS NO BODY: " + event.toString());
                    messageBody = mContext.getString(R.string.empty_message);
                    if (event.getType().equals(EVENT_TYPE_MESSAGE_ENCRYPTED))
                        messageBody = mContext.getString(R.string.unable_to_decrypt);
                }


                MatrixAddress addrSender = new MatrixAddress(event.sender);

                Message message = null;
                if (!isQuickReaction) {
                    message = downloadMedia(session, event, messageBody, addrSender);
                }

                if (message == null) {
                    message = new Message(messageBody);
                    message.setID(eventId);
                    message.setFrom(addrSender);
                    message.setDateTime(new Date(event.getOriginServerTs()));
                }

                if (replyToInfo != null) {
                    message.setReplyId(replyToInfo.first);
                }

                message.setReaction(isQuickReaction);

                boolean isFromMe = addrSender.mAddress.equals(mUser.getAddress().getAddress());

                boolean isEncrypted = mDataHandler.getRoom(event.roomId).isEncrypted();

                if (isFromMe) {
                    if (isEncrypted) {
                        message.setType(Imps.MessageType.OUTGOING_ENCRYPTED);
                    } else
                        message.setType(Imps.MessageType.OUTGOING);
                } else {
                    if (isEncrypted)
                        message.setType(Imps.MessageType.INCOMING_ENCRYPTED);
                    else
                        message.setType(Imps.MessageType.INCOMING);
                }

                boolean notifyUser = !isFromMe;

                if (notifyUser) {
                    //only notify if received in the last day, else we get swarmed
                    Date now = new Date();
                    if ((now.getTime() - event.getOriginServerTs()) > TIME_ONE_DAY_MS)
                        notifyUser = false;
                }

                session.onReceiveMessage(message, notifyUser);

                csa.setContactTyping(new Contact(addrSender), false);


            }
        }

    }

    private Message downloadMedia (ChatSession session, Event event, String downloadFileName, MatrixAddress addrSender)
    {
        JsonObject jObj = event.getContent().getAsJsonObject();

        if (jObj != null && jObj.has("msgtype")) {
            String messageType = jObj.get("msgtype").getAsString();
            String messageMimeType = null;

            if (messageType.equals("m.image")
                    || messageType.equals("m.file")
                    || messageType.equals("m.video")
                    || messageType.equals("m.audio")) {

                if (messageType.equals("m.file")) {
                    FileMessage fileMessage = JsonUtils.toFileMessage(event.getContent());
                    messageMimeType = fileMessage.getMimeType();
                } else if (messageType.equals("m.image")) {
                    ImageMessage fileMessage = JsonUtils.toImageMessage(event.getContent());
                    messageMimeType = fileMessage.getMimeType();
                } else if (messageType.equals("m.audio")) {
                    AudioMessage fileMessage = JsonUtils.toAudioMessage(event.getContent());
                    messageMimeType = fileMessage.getMimeType();
                } else if (messageType.equals("m.video")) {
                    VideoMessage fileMessage = JsonUtils.toVideoMessage(event.getContent());
                    messageMimeType = fileMessage.getMimeType();
                }

                final Message message = new Message(mContext.getString(R.string.loading));
                message.setID(event.eventId);
                message.setFrom(addrSender);
                message.setDateTime(new Date(event.getOriginServerTs()));
                message.setContentType(messageMimeType);

                String mediaUrl = null;
                String thumbUrl = null;
                String localFolder = addrSender.getAddress();
                EncryptedFileInfo encryptedFileInfo = null;
                EncryptedFileInfo encryptedThumbInfo = null;

                if (messageType.equals("m.file")) {
                    FileMessage fileMessage = JsonUtils.toFileMessage(event.getContent());
                    mediaUrl = fileMessage.getUrl();
                    encryptedFileInfo = fileMessage.file;
                } else if (messageType.equals("m.image")) {
                    ImageMessage fileMessage = JsonUtils.toImageMessage(event.getContent());
                    mediaUrl = fileMessage.getUrl();
                    encryptedFileInfo = fileMessage.file;
                } else if (messageType.equals("m.audio")) {
                    AudioMessage fileMessage = JsonUtils.toAudioMessage(event.getContent());
                    mediaUrl = fileMessage.getUrl();
                    encryptedFileInfo = fileMessage.file;
                } else if (messageType.equals("m.video")) {
                    VideoMessage fileMessage = JsonUtils.toVideoMessage(event.getContent());
                    mediaUrl = fileMessage.getUrl();
                    encryptedFileInfo = fileMessage.file;
                    thumbUrl = fileMessage.getThumbnailUrl();

                    if (fileMessage.info != null)
                        encryptedThumbInfo = fileMessage.info.thumbnail_file;
                }

                if (!TextUtils.isEmpty(mediaUrl)) {
                    String result = downloadMediaAsync(event, encryptedFileInfo, encryptedThumbInfo,
                            mediaUrl, thumbUrl, downloadFileName, localFolder);

                    if (!TextUtils.isEmpty(result)) {
                        message.setBody(result);
                    }
                }


                return message;


            }
        }

        return null;
    }

    private String downloadMediaAsync (Event event, EncryptedFileInfo encryptedFileInfo, EncryptedFileInfo encryptedThumbInfo,
                                       String mediaUrl, String thumbUrl, String downloadFileName, String localFolder)
    {
        MatrixDownloader dl = new MatrixDownloader();
        info.guardianproject.iocipher.File fileDownload = null;
        String result = null;

        try {
            boolean isEncrypted = null != encryptedFileInfo;

            String downloadableUrl = mSession.getContentManager().getDownloadableUrl(mediaUrl, isEncrypted);

                    //noinspection UnnecessaryLocalVariable
            String localFileNameEsc = downloadFileName;//should check this with regex
            info.guardianproject.iocipher.File fileDownloadDecrypted = dl.openSecureStorageFile(localFolder, localFileNameEsc);

            if (fileDownloadDecrypted.exists() && fileDownloadDecrypted.length() > 0)
            {
                //all set
                result = SecureMediaStore.vfsUri(fileDownloadDecrypted.getAbsolutePath()).toString();

            }
            else {
                if (isEncrypted)
                    fileDownload = dl.openSecureStorageFile(localFolder, localFileNameEsc + ".encrypted");
                else
                    fileDownload = dl.openSecureStorageFile(localFolder, localFileNameEsc);

                boolean downloaded = fileDownload.exists();

                if (!downloaded) {
                    OutputStream storageStream = new info.guardianproject.iocipher.FileOutputStream(fileDownload);
                    downloaded = dl.get(downloadableUrl, storageStream);
                }

                if (downloaded) {

                    if (isEncrypted) {
                        fileDownloadDecrypted = dl.openSecureStorageFile(localFolder, localFileNameEsc);
                        if (!fileDownloadDecrypted.exists()) {
                            OutputStream storageStream = new info.guardianproject.iocipher.FileOutputStream(fileDownloadDecrypted);
                            boolean success = MatrixDownloader.decryptAttachment(new FileInputStream(fileDownload), encryptedFileInfo, storageStream);
                        }

                        fileDownload.delete();
                        fileDownload = fileDownloadDecrypted;
                        result = SecureMediaStore.vfsUri(fileDownload.getAbsolutePath()).toString();
                    } else {
                        result = SecureMediaStore.vfsUri(fileDownload.getAbsolutePath()).toString();
                    }
                }


                try {
                    downloadableUrl = mSession.getContentManager().getDownloadableUrl(thumbUrl, isEncrypted);

                    if (!TextUtils.isEmpty(downloadableUrl)) {
                        info.guardianproject.iocipher.File fileDownloadThumb;

                        if (isEncrypted)
                            fileDownloadThumb = dl.openSecureStorageFile(localFolder, localFileNameEsc + ".thumb.encrypted");
                        else
                            fileDownloadThumb = dl.openSecureStorageFile(localFolder, localFileNameEsc + ".thumb.jpg");

                        OutputStream storageStream = new info.guardianproject.iocipher.FileOutputStream(fileDownloadThumb);
                        downloaded = dl.get(downloadableUrl, storageStream);

                        if (downloaded) {

                            if (isEncrypted) {
                                storageStream = new info.guardianproject.iocipher.FileOutputStream(new File(fileDownload.getAbsolutePath() + ".thumb.jpg"));

                                boolean success = MatrixDownloader.decryptAttachment(new FileInputStream(fileDownloadThumb), encryptedThumbInfo, storageStream);
                                fileDownloadThumb.delete();
                            }
                        }
                    }

                } catch (Exception e) {
                    debug("Error downloading file: " + thumbUrl, e);
                }
            }


        } catch (Exception e) {
            debug("Error downloading file: " + mediaUrl, e);
        }

        return result;
    }

    /**
     * Send a registration request with the given parameters.
     *
     * @param listener A listener to call back on registration success or failure.
     */
    public void register(String username, String password, final RegistrationManager.RegistrationListener listener) {
        mRegListener = listener;

        LoginRestClient client = new LoginRestClient(mConfig);

        RegistrationParams params = new RegistrationParams();
        params.initial_device_display_name = "Mobile";

        client.register(params, new ApiCallback<Credentials>() {
            @Override
            public void onNetworkError(Exception e) {
                mRegListener.onRegistrationFailed(e.getLocalizedMessage());
            }

            @Override
            public void onMatrixError(MatrixError e) {
                if (e.mStatus == 401) {
                    try {
                        RegistrationFlowResponse flow = JsonUtils.toRegistrationFlowResponse(e.mErrorBodyAsString);

                        if (flow == null) throw new NullPointerException(mContext.getString(R.string.internal_server_error));

                        mRm = new RegistrationManager(null);
                        mRm.setSupportedRegistrationFlows(flow);

                        // E-Mail and phone number flow steps currently unsupported.
                        if (mRm.isEmailRequired() || mRm.isPhoneNumberRequired()) {
                            mRm = null;
                            throw new Exception(mContext.getString(R.string.version_not_supported));
                        }

                        mRm.setHsConfig(mConfig);
                        mRm.setAccountData(username, password);
                        mRm.attemptRegistration(mContext, mRegListener);
                    }
                    catch (Exception exception) {
                        mRegListener.onRegistrationFailed(exception.getLocalizedMessage());
                    }
                }
                else {
                    mRegListener.onRegistrationFailed(e.getLocalizedMessage());
                }
            }

            @Override
            public void onUnexpectedError(Exception e) {
                mRegListener.onRegistrationFailed(e.getLocalizedMessage());
            }

            @Override
            public void onSuccess(Credentials credentials) {
                // Should never be called: We're trying with insufficient credentials, so
                // we can't successfully register here. This is just to acquire the registration flow.
            }
        });
    }

    public void continueRegister(String captchaResponse, boolean termsApproved) {
        if (mRm == null) return;

        if (captchaResponse != null) mRm.setCaptchaResponse(captchaResponse);

        if (termsApproved) mRm.setTermsApproved();

        mRm.attemptRegistration(mContext, mRegListener);
    }

    public Uri getHomeServer() {
        return mConfig.getHomeserverUri();
    }

    public interface LoginListener {
        void onLoginSuccess();

        void onLoginFailed(String message);

    }

    private void downloadAvatar(final String address, final String url) {
        lbqAvatars.add(new Pair<>(address, url));
    }

    private void downloadAvatarSync(final String address, final String url) {

        boolean hasAvatar = DatabaseUtils.doesAvatarHashExist(mContext.getContentResolver(), Imps.Avatars.CONTENT_URI, address, url);

        if (!hasAvatar) {

            try {
                info.guardianproject.iocipher.File fileAvatar = DatabaseUtils.openSecureStorageFile(ROOM_AVATAR_ACCESS, address);
                info.guardianproject.iocipher.FileOutputStream fos = new info.guardianproject.iocipher.FileOutputStream(fileAvatar);
                new MatrixDownloader().get(url, fos);

                //   if (baos != null && baos.size() > 0)
                //     setAvatar(address, baos.toByteArray(), url);

            } catch (Exception e) {
                debug("downloadAvatar error", e);
            }
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean hasAvatar(String address, String downloadUrl) {
        return DatabaseUtils.doesAvatarHashExist(mContext.getContentResolver(), Imps.Avatars.CONTENT_URI, address, downloadUrl);
    }

    @Override
    public void searchForUser(String search, IContactListListener listener) {
        if (mSession != null) {
            mSession.searchUsers(search, 10, null, new ApiCallback<SearchUsersResponse>() {
                @Override
                public void onNetworkError(Exception e) {

                }

                @Override
                public void onMatrixError(MatrixError matrixError) {

                }

                @Override
                public void onUnexpectedError(Exception e) {

                }

                @Override
                public void onSuccess(SearchUsersResponse searchUsersResponse) {

                    if (searchUsersResponse != null && searchUsersResponse.results != null) {

                        Contact[] contacts = new Contact[searchUsersResponse.results.size()];
                        int i = 0;
                        for (User user : searchUsersResponse.results) {
                            if (user.user_id != null && user.displayname != null)
                                contacts[i++] = new Contact(new MatrixAddress(user.user_id), user.displayname, Imps.Contacts.TYPE_NORMAL);
                        }

                        if (listener != null) {
                            if (contacts.length > 0) {
                                try {
                                    listener.onContactsPresenceUpdate(contacts);
                                } catch (RemoteException e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    }


                }
            });
        }
    }

    private void handleRoomInvite(Room room, String sender) {
        MatrixAddress addrRoom = new MatrixAddress(room.getRoomId());
        MatrixAddress addrSender = null;

        if (!TextUtils.isEmpty(sender))
            addrSender = new MatrixAddress(sender);

        if (room.isInvited()) {

            ChatGroup participant = mChatGroupManager.getChatGroup(addrRoom);
            if (TextUtils.isEmpty(participant.getName()))
                participant.setName(room.getRoomDisplayName(mContext, mContext.getString(R.string.room_displayname_empty_room)));

            participant.setJoined(false);
            ChatSession session = mChatSessionManager.getSession(room.getRoomId());

            if (session == null) {
                mChatSessionManager.createChatSession(participant, true);

                ChatSessionAdapter csa = mChatSessionManager.getChatSessionAdapter(room.getRoomId());
                csa.setLastMessage(mContext.getString(R.string.room_invited));

                Invitation invite = new Invitation(room.getRoomId(), csa.getId(), addrRoom, addrSender, room.getRoomDisplayName(mContext));
                mChatGroupManager.notifyGroupInvitation(invite);
            }


        } else if (room.isMember()) {
            ChatSession session = mChatSessionManager.getSession(room.getRoomId());

            if (session == null) {
                ChatGroup participant = mChatGroupManager.getChatGroup(addrRoom);
                if (TextUtils.isEmpty(participant.getName()))
                    participant.setName(room.getRoomDisplayName(mContext));
                mChatSessionManager.createChatSession(participant, true);
            }
        }
    }

    LinkedBlockingQueue<Pair<String, String>> lbqAvatars = new LinkedBlockingQueue<>();
    Timer mTimerAvatarLoader;

    private void initAvatarLoader() {
        mTimerAvatarLoader = new Timer();

        mTimerAvatarLoader.schedule(new TimerTask() {

            public void run() {

                int maxLoad = 3;
                int i = 0;

                while (lbqAvatars.peek() != null && i++ < maxLoad) {
                    Pair<String, String> pair = lbqAvatars.poll();
                    if (pair != null) downloadAvatarSync(pair.first, pair.second);
                }

            }

        }, 0, 20000);
    }

    /**
     * Get "reply to" info from the event. Also, if the event is a reaction, return that.
     *
     * @param event A Matrix event.
     * @return a Pair<String,String> of originalEventId, reaction.
     */
    private Pair<String, String> getReplyToFromEvent(Event event) {
        Event readEvent = event.getClearEvent() != null ? event.getClearEvent() : event;
        if (!readEvent.getContent().isJsonNull()) {
            JsonObject json = readEvent.getContent().getAsJsonObject();
            if (json.has("m.relates_to")) {
                JsonObject jObj = json.getAsJsonObject("m.relates_to");

                String reaction = null;
                String eventId = null;

                if (jObj != null) {
                    if (jObj.has("key")) {
                        reaction = jObj.get("key").getAsString();
                    }
                    if (jObj.has("event_id")) {
                        eventId = jObj.get("event_id").getAsString();
                    } else if (jObj.has("m.in_reply_to")) {
                        JsonObject jObj2 = jObj.getAsJsonObject("m.in_reply_to");
                        if (jObj2 != null && jObj2.has("event_id")) {
                            eventId = jObj2.get("event_id").getAsString();
                        }
                    }
                }
                return new Pair<>(eventId, reaction);
            }
        }
        return null;
    }

    @SuppressWarnings({"unused", "RedundantSuppression"})
    public Collection<Event> getMessages(String roomId) {
        return mStore.getRoomMessages(roomId);
    }
}
