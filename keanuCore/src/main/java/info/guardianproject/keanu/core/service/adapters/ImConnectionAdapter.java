/*
 * Copyright (C) 2007-2008 Esmertec AG. Copyright (C) 2007-2008 The Android Open
 * Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package info.guardianproject.keanu.core.service.adapters;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import info.guardianproject.iocipher.File;
import info.guardianproject.iocipher.FileInputStream;
import info.guardianproject.keanu.core.model.ChatGroupManager;
import info.guardianproject.keanu.core.model.ConnectionListener;
import info.guardianproject.keanu.core.model.Contact;
import info.guardianproject.keanu.core.model.ImConnection;
import info.guardianproject.keanu.core.model.ImErrorInfo;
import info.guardianproject.keanu.core.model.ImException;
import info.guardianproject.keanu.core.model.Invitation;
import info.guardianproject.keanu.core.model.InvitationListener;
import info.guardianproject.keanu.core.model.Presence;
import info.guardianproject.keanu.core.provider.Imps;
import info.guardianproject.keanu.core.service.IChatSessionManager;
import info.guardianproject.keanu.core.service.IConnectionListener;
import info.guardianproject.keanu.core.service.IContactListListener;
import info.guardianproject.keanu.core.service.IContactListManager;
import info.guardianproject.keanu.core.service.IImConnection;
import info.guardianproject.keanu.core.service.IInvitationListener;
import info.guardianproject.keanu.core.service.RemoteImService;
import info.guardianproject.keanu.core.tasks.ChatSessionInitTask;
import info.guardianproject.keanu.core.util.Debug;
import info.guardianproject.keanu.core.util.UploadProgressListener;

import static info.guardianproject.keanu.core.KeanuConstants.LOG_TAG;

public class ImConnectionAdapter extends IImConnection.Stub {

    private static final String[] SESSION_COOKIE_PROJECTION = { Imps.SessionCookies.NAME,
                                                               Imps.SessionCookies.VALUE, };

    private static final int COLUMN_SESSION_COOKIE_NAME = 0;
    private static final int COLUMN_SESSION_COOKIE_VALUE = 1;

    ImConnection mConnection;
    private ConnectionListenerAdapter mConnectionListener;
    private InvitationListenerAdapter mInvitationListener;

    final RemoteCallbackList<IConnectionListener> mRemoteConnListeners;

    ChatSessionManagerAdapter mChatSessionManager;
    ContactListManagerAdapter mContactListManager;

    ChatGroupManager mGroupManager;
    RemoteImService mService;

    long mProviderId = -1;
    long mAccountId = -1;
    boolean mAutoLoadContacts;
    int mConnectionState = ImConnection.DISCONNECTED;

    public ImConnectionAdapter(long providerId, long accountId, ImConnection connection, RemoteImService service) {
        mProviderId = providerId;
        mAccountId = accountId;
        mConnection = connection;
        mService = service;
        mConnectionListener = new ConnectionListenerAdapter();
        mConnection.addConnectionListener(mConnectionListener);

        mGroupManager = mConnection.getChatGroupManager();
        mInvitationListener = new InvitationListenerAdapter();
        mGroupManager.setInvitationListener(mInvitationListener);

        mChatSessionManager = new ChatSessionManagerAdapter(this);
        mContactListManager = new ContactListManagerAdapter(this);
        mRemoteConnListeners = new RemoteCallbackList<>();

    }

    public ImConnection getAdaptee() {
        return mConnection;
    }

    public RemoteImService getContext() {
        return mService;
    }

    @Override
    public long getProviderId() {
        return mProviderId;
    }

    @Override
    public long getAccountId() {
        return mAccountId;
    }

    @Override
    public boolean isUsingTor() {
        return mConnection.isUsingTor();
    }

    @Override
    public int[] getSupportedPresenceStatus() {
        return mConnection.getSupportedPresenceStatus();
    }

    public void networkTypeChanged() {
        mConnection.networkTypeChanged();
    }

    public boolean reestablishSession() {
        mConnectionState = ImConnection.LOGGING_IN;

        ContentResolver cr = mService.getContentResolver();
        if ((mConnection.getCapability() & ImConnection.CAPABILITY_SESSION_REESTABLISHMENT) != 0) {
            Map<String, String> cookie = querySessionCookie(cr);
            if (cookie != null) {
                RemoteImService.debug("re-establish session");
                try {
                    mConnection.reestablishSessionAsync(cookie);
                    return true;
                } catch (IllegalArgumentException e) {
                    RemoteImService.debug("Invalid session cookie, probably modified by others.");
                    clearSessionCookie(cr);
                }
            }
        }

        return false;
    }

    private Uri getSessionCookiesUri() {
        Uri.Builder builder = Imps.SessionCookies.CONTENT_URI_SESSION_COOKIES_BY.buildUpon();
        ContentUris.appendId(builder, mProviderId);
        ContentUris.appendId(builder, mAccountId);

        return builder.build();
    }

    @Override
    public void login(final String passwordTemp, final boolean autoLoadContacts, final boolean retry) {
        do_login(passwordTemp, autoLoadContacts, retry);

    }

    public void do_login(String passwordTemp, boolean autoLoadContacts, boolean retry) {

        mAutoLoadContacts = autoLoadContacts;
        mConnectionState = ImConnection.LOGGING_IN;

        mConnection.loginAsync(mAccountId, passwordTemp, mProviderId, retry);


    }

   
    
    
    private void loadSavedPresence ()
    {
        ContentResolver cr =  mService.getContentResolver();
        // Imps.ProviderSettings.setPresence(cr, mProviderId, status, statusText);
         int presenceState = Imps.ProviderSettings.getIntValue(cr, mProviderId, Imps.ProviderSettings.PRESENCE_STATE);
         String presenceStatusMessage = Imps.ProviderSettings.getStringValue(cr, mProviderId, Imps.ProviderSettings.PRESENCE_STATUS_MESSAGE);

         if (presenceState != -1)
         {
             Presence presence = new Presence();
             presence.setStatus(presenceState);
             presence.setStatusText(presenceStatusMessage);
             try {
                 mConnection.updateUserPresenceAsync(presence);
             } catch (ImException e) {
                 Log.e(LOG_TAG,"unable able to update presence",e);
             }
         }
    }

    @Override
    public void sendHeartbeat() throws RemoteException {

        if (mConnection != null)
            mConnection.sendHeartbeat(mService.getHeartbeatInterval());

    }

    @Override
    public void setProxy(String type, String host, int port) throws RemoteException {
        mConnection.setProxy(type, host, port);
    }

    private HashMap<String, String> querySessionCookie(ContentResolver cr) {
        Cursor c = cr.query(getSessionCookiesUri(), SESSION_COOKIE_PROJECTION, null, null, null);
        if (c == null) {
            return null;
        }

        HashMap<String, String> cookie = null;
        if (c.getCount() > 0) {
            cookie = new HashMap<String, String>();
            while (c.moveToNext()) {
                cookie.put(c.getString(COLUMN_SESSION_COOKIE_NAME),
                        c.getString(COLUMN_SESSION_COOKIE_VALUE));
            }
        }

        c.close();
        return cookie;
    }

    public void clearMemory ()
    {
        mChatSessionManager.closeAllChatSessions();
    }

    @Override
    public void logout(boolean fullLogout) {
       // OtrChatManager.endSessionsForAccount(mConnection.getLoginUser());
        mConnectionState = ImConnection.LOGGING_OUT;
        mConnection.logout(fullLogout);
    }

    @Override
    public void cancelLogin() {
        if (mConnectionState >= ImConnection.LOGGED_IN) {
            // too late
            return;
        }
        mConnectionState = ImConnection.LOGGING_OUT;
        mConnection.logout(false);
    }

    public void suspend() {
        mConnectionState = ImConnection.SUSPENDING;
        mConnection.suspend();
    }

    @Override
    public void registerConnectionListener(IConnectionListener listener) {
        if (listener != null) {
            mRemoteConnListeners.register(listener);
        }
    }

    @Override
    public void unregisterConnectionListener(IConnectionListener listener) {
        if (listener != null) {
            mRemoteConnListeners.unregister(listener);
        }
    }

    @Override
    public void setInvitationListener(IInvitationListener listener) {
        if (mInvitationListener != null) {
            mInvitationListener.mRemoteListener = listener;
        }
    }



    @Override
    public IChatSessionManager getChatSessionManager() {
        return mChatSessionManager;
    }

    @Override
    public IContactListManager getContactListManager() {
        return mContactListManager;
    }

    @Override
    public int getChatSessionCount() {
        if (mChatSessionManager == null) {
            return 0;
        }
        return mChatSessionManager.getChatSessionCount();
    }

    public Contact getLoginUser() {
        return mConnection.getLoginUser();
    }

    @Override
    public Presence getUserPresence() {
        return mConnection.getUserPresence();
    }

    @Override
    public int updateUserPresence(Presence newPresence) {
        try {


            mConnection.updateUserPresenceAsync(newPresence);


        } catch (ImException e) {
            return e.getImError().getCode();
        }

        return ImErrorInfo.NO_ERROR;
    }

    @Override
    public int getState() {
        return mConnectionState;
    }

    @Override
    public void rejectInvitation(long id) {
        handleInvitation(id, false);
    }

    @Override
    public void acceptInvitation(long id) {
        handleInvitation(id, true);
    }

    private void handleInvitation(long id, boolean accept) {
        if (mGroupManager == null) {
            return;
        }
        ContentResolver cr = mService.getContentResolver();
        Cursor c = cr.query(ContentUris.withAppendedId(Imps.Invitation.CONTENT_URI, id), null,
                null, null, null);
        if (c == null) {
            return;
        }
        if (c.moveToFirst()) {
            String inviteId = c.getString(c.getColumnIndexOrThrow(Imps.Invitation.INVITE_ID));
            int status;
            if (accept) {
                mGroupManager.acceptInvitationAsync(inviteId);
                status = Imps.Invitation.STATUS_ACCEPTED;
            } else {
                mGroupManager.rejectInvitationAsync(inviteId);
                status = Imps.Invitation.STATUS_REJECTED;
            }
            // TODO c.updateInt(c.getColumnIndexOrThrow(Imps.Invitation.STATUS), status);
            // c.commitUpdates();
        }
        c.close();
    }

    void saveSessionCookie(ContentResolver cr) {
        Map<String, String> cookies = mConnection.getSessionContext();

        int i = 0;
        ContentValues[] valuesList = new ContentValues[cookies.size()];

        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            ContentValues values = new ContentValues(2);

            values.put(Imps.SessionCookies.NAME, entry.getKey());
            values.put(Imps.SessionCookies.VALUE, entry.getValue());

            valuesList[i++] = values;
        }

        cr.bulkInsert(getSessionCookiesUri(), valuesList);
    }

    void clearSessionCookie(ContentResolver cr) {
        cr.delete(getSessionCookiesUri(), null, null);
    }

    void updateAccountStatusInDb() {
        Presence p = getUserPresence();
        int presenceStatus = Imps.Presence.OFFLINE;
        int connectionStatus = convertConnStateForDb(mConnectionState);

        if (p != null) {
            presenceStatus = ContactListManagerAdapter.convertPresenceStatus(p);
        }

        ContentResolver cr = mService.getContentResolver();
        Uri uri = Imps.AccountStatus.CONTENT_URI;
        ContentValues values = new ContentValues();

        values.put(Imps.AccountStatus.ACCOUNT, mAccountId);
        values.put(Imps.AccountStatus.PRESENCE_STATUS, presenceStatus);
        values.put(Imps.AccountStatus.CONNECTION_STATUS, connectionStatus);

        cr.insert(uri, values);
    }

    private static int convertConnStateForDb(int state) {
        switch (state) {
        case ImConnection.DISCONNECTED:
        case ImConnection.LOGGING_OUT:
            return Imps.ConnectionStatus.OFFLINE;

        case ImConnection.LOGGING_IN:
            return Imps.ConnectionStatus.CONNECTING;

        case ImConnection.LOGGED_IN:
            return Imps.ConnectionStatus.ONLINE;

        case ImConnection.SUSPENDED:
        case ImConnection.SUSPENDING:
            return Imps.ConnectionStatus.SUSPENDED;

        default:
            return Imps.ConnectionStatus.OFFLINE;
        }
    }

    final class ConnectionListenerAdapter implements ConnectionListener {
        @Override
        public void onStateChanged(final int state, final ImErrorInfo error) {

            if (state != ImConnection.DISCONNECTED) {
                mConnectionState = state;
            }

            if (state == ImConnection.LOGGED_IN && mConnectionState == ImConnection.LOGGING_OUT) {

                // A bit tricky here. The engine did login successfully
                // but the notification comes a bit late; user has already
                // issued a cancelLogin() and that cannot be undone. Here
                // we have to ignore the LOGGED_IN event and wait for
                // the upcoming DISCONNECTED.
                return;
            }

            updateAccountStatusInDb();

            final int N = mRemoteConnListeners.beginBroadcast();
            for (int i = 0; i < N; i++) {
                IConnectionListener listener = mRemoteConnListeners.getBroadcastItem(i);
                try {
                    listener.onStateChanged(ImConnectionAdapter.this, state, error);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.
                }
            }

            mRemoteConnListeners.finishBroadcast();


            if (state == ImConnection.LOGGED_IN)
            {

            }

            ContentResolver cr = mService.getContentResolver();
            if (state == ImConnection.LOGGED_IN) {
                if ((mConnection.getCapability() & ImConnection.CAPABILITY_SESSION_REESTABLISHMENT) != 0) {
                    saveSessionCookie(cr);
                }

                /**
                ArrayList<ChatSessionAdapter> adapters = new ArrayList<ChatSessionAdapter>(mChatSessionManager.mActiveChatSessionAdapters.values());
                for (ChatSessionAdapter session : adapters) {
                    session.sendPostponedMessages();
                }**/

                loadSavedPresence();
                

            } else if (state == ImConnection.DISCONNECTED) {
                clearSessionCookie(cr);
                // mContactListManager might still be null if we fail
                // immediately in loginAsync (say, an invalid host URL)
                if (mContactListManager != null) { // n8fr8 2015-01-21 Why are we clearing this?
                  mContactListManager.clearOnLogout();
                }

                mConnectionState = state;
            } else if (state == ImConnection.SUSPENDED && error != null) {

                // re-establish failed, schedule to retry
                mService.scheduleReconnect(5000);

            }



            if (state == ImConnection.DISCONNECTED) {
                // NOTE: if this logic is changed, the logic in ImApp.MyConnListener must be changed to match
                mService.removeConnection(ImConnectionAdapter.this);

            }
        }

        @Override
        public void onUserPresenceUpdated() {
            updateAccountStatusInDb();

            final int N = mRemoteConnListeners.beginBroadcast();
            for (int i = 0; i < N; i++) {
                IConnectionListener listener = mRemoteConnListeners.getBroadcastItem(i);
                try {
                    listener.onUserPresenceUpdated(ImConnectionAdapter.this);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.
                }
            }
            mRemoteConnListeners.finishBroadcast();
        }

        @Override
        public void onUpdatePresenceError(final ImErrorInfo error) {
            final int N = mRemoteConnListeners.beginBroadcast();
            for (int i = 0; i < N; i++) {
                IConnectionListener listener = mRemoteConnListeners.getBroadcastItem(i);
                try {
                    listener.onUpdatePresenceError(ImConnectionAdapter.this, error);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.
                }
            }
            mRemoteConnListeners.finishBroadcast();
        }

        @Override
        public void uploadComplete(String url) {

        }

    }

    final class InvitationListenerAdapter implements InvitationListener {
        IInvitationListener mRemoteListener;

        @Override
        public void onGroupInvitation(Invitation invitation) {
            String sender = invitation.getSender().getUser();
            ContentValues values = new ContentValues(7);
            values.put(Imps.Invitation.PROVIDER, mProviderId);
            values.put(Imps.Invitation.ACCOUNT, mAccountId);
            values.put(Imps.Invitation.INVITE_ID, invitation.getInviteID());
            values.put(Imps.Invitation.SENDER, sender);
            values.put(Imps.Invitation.GROUP_NAME, invitation.getReason());
            values.put(Imps.Invitation.NOTE, invitation.getReason());
            values.put(Imps.Invitation.STATUS, Imps.Invitation.STATUS_PENDING);

            ContentResolver resolver = mService.getContentResolver();
            Uri uri = resolver.insert(Imps.Invitation.CONTENT_URI, values);
            long id = ContentUris.parseId(uri);
            try {
                if (mRemoteListener != null) {
                    mRemoteListener.onGroupInvitation(id);
                    return;
                }
            } catch (RemoteException e) {
                RemoteImService.debug("onGroupInvitation: dead listener " + mRemoteListener
                                      + "; removing", e);
                mRemoteListener = null;
            }
            // No listener registered or failed to notify the listener, send a
            // notification instead.
            mService.getStatusBarNotifier().notifyGroupInvitation(mProviderId, mAccountId, id,
                    invitation);
        }
    }



    public void uploadContent (String mediaPath, String contentTitle, String mimeType, final IConnectionListener listener)
    {
        if (mConnection != null)
        {

            InputStream is = null;
            try {

                Uri uriMedia = Uri.parse(mediaPath);
                if (TextUtils.isEmpty(uriMedia.getScheme())||uriMedia.getScheme().equals("vfs"))
                {
                    is = new FileInputStream(new File(uriMedia.getPath()));
                }
                else
                    is = getContext().getContentResolver().openInputStream(uriMedia);

                mConnection.uploadContent(is, contentTitle, mimeType, new ConnectionListener() {
                    @Override
                    public void onStateChanged(int state, ImErrorInfo error) {

                    }

                    @Override
                    public void onUserPresenceUpdated() {

                    }

                    @Override
                    public void onUpdatePresenceError(ImErrorInfo error) {

                    }

                    @Override
                    public void uploadComplete(String url) {
                        try {
                            listener.uploadComplete(url);
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }
                });

            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }

        }

    }


    public String getDownloadUrl (String identifier)
    {
        if (mConnection != null)
            return mConnection.getDownloadUrl(identifier);

        return null;
    }

    public void changeNickname (String nickname)
    {
        if (mConnection != null)
            mConnection.changeNickname(nickname);
    }

    @Override
    public void sendMessageRead (String roomId, String msgId)
    {
        if (mConnection != null)
            mConnection.sendMessageRead(roomId, msgId);
    }

    public void sendTypingStatus (String to, boolean isTyping)
    {
        if (mConnection != null)
            mConnection.sendTypingStatus(to,isTyping);

    }

    public void broadcastMigrationIdentity(String address)
    {
        if (mConnection != null)
            mConnection.broadcastMigrationIdentity(address);

    }

    public List getFingerprints (String address)
    {
        if (mConnection != null)
            return mConnection.getFingerprints(address);
        else
            return null;
    }

    public void setDeviceVerified (String address, String device, boolean verified)
    {
        if (mConnection != null)
            mConnection.setDeviceVerified(address, device, verified);

    }

    public void searchForUser (String searchString, IContactListListener listener)
    {
        if (mConnection != null)
            mConnection.searchForUser(searchString, listener);
    }
    /**
    public String sendMediaMessage (String recipient, Uri uriMedia, String fileName, String mimeType, long fileSize, boolean doEncryption, UploadProgressListener listener)
    {
        return mConnection.sendMediaMessage(recipient, uriMedia, fileName, mimeType, fileSize, doEncryption, listener);
    }**/

}
