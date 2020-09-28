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
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.BaseColumns;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import org.apache.commons.codec.DecoderException;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URLConnection;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import info.guardianproject.keanu.core.R;
import info.guardianproject.keanu.core.model.ChatGroup;
import info.guardianproject.keanu.core.model.ChatGroupManager;
import info.guardianproject.keanu.core.model.ChatSession;
import info.guardianproject.keanu.core.model.ChatSessionListener;
import info.guardianproject.keanu.core.model.Contact;
import info.guardianproject.keanu.core.model.GroupListener;
import info.guardianproject.keanu.core.model.GroupMemberListener;
import info.guardianproject.keanu.core.model.ImConnection;
import info.guardianproject.keanu.core.model.ImEntity;
import info.guardianproject.keanu.core.model.ImErrorInfo;
import info.guardianproject.keanu.core.model.Message;
import info.guardianproject.keanu.core.model.MessageListener;
import info.guardianproject.keanu.core.model.Presence;
import info.guardianproject.keanu.core.model.impl.BaseAddress;
import info.guardianproject.keanu.core.provider.Imps;
import info.guardianproject.keanu.core.service.IChatListener;
import info.guardianproject.keanu.core.service.IChatSession;
import info.guardianproject.keanu.core.service.IChatSessionListener;
import info.guardianproject.keanu.core.service.IDataListener;
import info.guardianproject.keanu.core.service.RemoteImService;
import info.guardianproject.keanu.core.service.StatusBarNotifier;
import info.guardianproject.keanu.core.util.DatabaseUtils;
import info.guardianproject.keanu.core.util.SystemServices;
import info.guardianproject.keanu.core.util.UploadProgressListener;

import static cz.msebera.android.httpclient.conn.ssl.SSLConnectionSocketFactory.TAG;
import static info.guardianproject.keanu.core.KeanuConstants.LOG_TAG;
import static info.guardianproject.keanu.core.KeanuConstants.SMALL_AVATAR_HEIGHT;
import static info.guardianproject.keanu.core.KeanuConstants.SMALL_AVATAR_WIDTH;
import static info.guardianproject.keanu.core.provider.Imps.ChatsColumns.LAST_UNREAD_MESSAGE;

public class ChatSessionAdapter extends IChatSession.Stub {

    private static final String NON_CHAT_MESSAGE_SELECTION = Imps.Messages.TYPE + "!="
                                                             + Imps.MessageType.INCOMING + " AND "
                                                             + Imps.Messages.TYPE + "!="

                                                             + Imps.MessageType.OUTGOING;

    /** The registered remote listeners. */
  //  final RemoteCallbackList<IChatListener> mRemoteListeners = new RemoteCallbackList<IChatListener>();
    final ArrayList<IChatListener> mRemoteListeners = new ArrayList<>();

    ImConnectionAdapter mConnection;
    ChatSessionManagerAdapter mChatSessionManager;


    ChatSession mChatSession;
    ListenerAdapter mListenerAdapter;
    boolean mIsGroupChat;
    StatusBarNotifier mStatusBarNotifier;

    private ContentResolver mContentResolver;
    /*package*/Uri mChatURI;

    private Uri mMessageURI;

    private boolean mConvertingToGroupChat;

    private HashMap<String, Integer> mContactStatusMap = new HashMap<String, Integer>();

    private boolean mHasUnreadMessages;

    private RemoteImService service = null;

    private IDataListener mDataListener;
    private DataHandlerListenerImpl mDataHandlerListener;

    private boolean mAcceptTransfer = false;
    private boolean mWaitingForResponse = false;
    private boolean mAcceptAllTransfer = true;//TODO set this via preference, but default true
    private String mLastFileUrl = null;

    private long mContactId;
    private boolean mIsMuted = false;
    private boolean mUseEncryption = false;
    private String mNickname = null;

    private ChatGroup mGroup = null;

    public ChatSessionAdapter(ChatSession chatSession, ChatGroup group, ImConnectionAdapter connection, boolean isNewSession) {

        mChatSession = chatSession;
        mConnection = connection;

        service = connection.getContext();
        mContentResolver = service.getContentResolver();
        mStatusBarNotifier = service.getStatusBarNotifier();
        mChatSessionManager = (ChatSessionManagerAdapter) connection.getChatSessionManager();

        mListenerAdapter = new ListenerAdapter();

        mDataHandlerListener = new DataHandlerListenerImpl();

        init(group, isNewSession);

        initMuted();
        initUseEncryption();

    }

    public ListenerAdapter getListenerAdapter ()
    {
        return mListenerAdapter;
    }

    public ChatSession getChatSession ()
    {
        return mChatSession;
    }


    private int lastPresence = -1;

    public void presenceChanged (int newPresence)
    {

      //  ((Contact) mChatSession.getParticipant()).getPresence().setStatus(newPresence);

     //   if (lastPresence != newPresence && newPresence == Presence.AVAILABLE)
       //     sendPostponedMessages();

        lastPresence = newPresence;

    }

    private void init(ChatGroup group, boolean isNewSession) {

        mGroup = group;
        mIsGroupChat = true;
        mNickname = group.getName();

        mContactId = insertOrUpdateGroupContactInDb(group, isNewSession);
        group.addMemberListener(mListenerAdapter);
        mChatSession.setMessageListener(mListenerAdapter);

        try {
            mChatURI = ContentUris.withAppendedId(Imps.Chats.CONTENT_URI, mContactId);
        
            mMessageURI = Imps.Messages.getContentUriByThreadId(mContactId);
            if (!hasLastMessage())
                setLastMessage(" ");

            /**
            for (Contact c : group.getMembers()) {
                mContactStatusMap.put(c.getName(), c.getPresence().getStatus());
            }**/
            
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        
    }

    public void update ()
    {
        mContactId = insertOrUpdateGroupContactInDb((ChatGroup)mChatSession.getParticipant(), false);
    }

    /**
    private void init(Contact contact, boolean isNewSession) {
        mIsGroupChat = false;
        mNickname = contact.getName();

        ContactListManagerAdapter listManager = (ContactListManagerAdapter) mConnection.getContactListManager();
        mChatSession.setMessageListener(mListenerAdapter);

        mContactId = listManager.queryOrInsertContact(contact);

        mChatURI = ContentUris.withAppendedId(Imps.Chats.CONTENT_URI, mContactId);

        if (isNewSession)
            setLastMessage(null);

        mMessageURI = Imps.Messages.getContentUriByThreadId(mContactId);

        mContactStatusMap.put(contact.getName(), contact.getPresence().getStatus());


    }**/

    public void reInit ()
    {
       // setLastMessage(null);

    }

    private ChatGroupManager getGroupManager() {
        return mConnection.getAdaptee().getChatGroupManager();
    }

    public ChatSession getAdaptee() {
        return mChatSession;
    }

    public Uri getChatUri() {
        return mChatURI;
    }

    public String[] getParticipants() {
        if (mIsGroupChat) {
            Contact self = mConnection.getLoginUser();
            Collection<Contact> members = mGroup.getMembers();
            if (members.size() > 0) {
                String[] result = new String[members.size()];
                int index = 0;
                for (Contact c : members) {
                    result[index++] = c.getAddress().getAddress();
                }
                return result;
            }
            else
                return null;
        } else {

            return new String[] { mChatSession.getParticipant().getAddress().getAddress() };
        }
    }

    /**
     * Convert this chat session to a group chat. If it's already a group chat,
     * nothing will happen. The method works in async mode and the registered
     * listener will be notified when it's converted to group chat successfully.
     *
     * Note that the method is not thread-safe since it's always called from the
     * UI and Android uses single thread mode for UI.
     */
    public void convertToGroupChat(String nickname) {
        if (mIsGroupChat || mConvertingToGroupChat) {
            return;
        }

        mConvertingToGroupChat = true;
        new ChatConvertor().convertToGroupChat(nickname);
    }

    public boolean isGroupChatSession() {
        return mIsGroupChat;
    }

    public String getName() {

        if (isGroupChatSession())
            return ((ChatGroup)mChatSession.getParticipant()).getName();
        else
            return ((Contact)mChatSession.getParticipant()).getName();

    }

    public String getAddress() {
        return mChatSession.getParticipant().getAddress().getAddress();
    }

    public long getId() {
        return ContentUris.parseId(mChatURI);
    }

    public void inviteContact(String address) {
        if (!mIsGroupChat) {
            return;
        }

        Contact contact = null;
        try {
            contact = mConnection.getContactListManager().getContactByAddress(address);
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        if (contact == null)
            contact = new Contact(new BaseAddress(address),address,Imps.Contacts.TYPE_NORMAL);

        getGroupManager().inviteUserAsync((ChatGroup) mChatSession.getParticipant(), contact);

    }

    public void leave() {
        ChatGroup group = (ChatGroup) mChatSession.getParticipant();

        mContentResolver.delete(mMessageURI, null, null);
        mContentResolver.delete(mChatURI, null, null);
        mStatusBarNotifier.dismissChatNotification(mConnection.getProviderId(), getAddress());
        mChatSessionManager.closeChatSession(this);

        if (mIsGroupChat) {
            getGroupManager().leaveChatGroupAsync(group);
            try {
                mConnection.getContactListManager().removeContact(group.getAddress().getAddress());
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

    }

    public void leaveIfInactive() {
    //    if (mChatSession.getHistoryMessages().isEmpty()) {
            leave();
      //  }
    }

    public void sendMessage(String text, boolean isResend, boolean isEphemeral, boolean setLastMessage, String replyId) {

        if (mConnection.getState() != ImConnection.LOGGED_IN) {
            // connection has been suspended, save the message without send it
            long now = System.currentTimeMillis();
            insertMessageInDb(null, text, now, Imps.MessageType.QUEUED, null, replyId);
            return;
        }

        Message msg = new Message(text);
        msg.setID(nextID());

        msg.setFrom(mConnection.getLoginUser().getAddress());

        msg.setType(Imps.MessageType.QUEUED);

        msg.setReplyId(replyId);

        long sendTime = System.currentTimeMillis();

        if (!isResend) {

            if (!isEphemeral) {
                insertMessageInDb(null, text, sendTime, msg.getType(), 0, msg.getID(), null, replyId);

            }
            else
            {
                //add empty message
                insertMessageInDb(null, "", sendTime, Imps.MessageType.STATUS, 0, msg.getID(), null, replyId);
            }
        }

        int newType = mChatSession.sendMessageAsync(msg, new ChatSessionListener() {

            @Override
            public void onChatSessionCreated(ChatSession session) {

            }

            @Override
            public void onMessageSendSuccess(Message msg, String newMsgId) {

                long sendTime = new Date().getTime();

                if (msg.getDateTime() != null)
                    sendTime = msg.getDateTime().getTime();

              //  deleteMessageInDb(msg.getID());

                updateMessageInDb(msg.getID(), msg.getType(), sendTime, null, newMsgId);

                 msg.setID(newMsgId);

                if (setLastMessage)
                    setLastMessage(text, sendTime);

            }

            @Override
            public void onMessageSendFail(Message msg, String newMsgId) {
                long sendTime = new Date().getTime();

                if (msg.getDateTime() != null)
                    sendTime = msg.getDateTime().getTime();

                updateMessageInDb(msg.getID(), msg.getType(), sendTime, null, newMsgId);

                msg.setID(newMsgId);

            }

            @Override
            public void onMessageSendQueued(Message msg, String newMsgId) {
                long sendTime = new Date().getTime();

                if (msg.getDateTime() != null)
                    sendTime = msg.getDateTime().getTime();

                updateMessageInDb(msg.getID(), Imps.MessageType.SENDING, sendTime, null, newMsgId);

                msg.setID(newMsgId);

            }
        });



    }

    public void sendReaction(String text, boolean isResend, String replyId) {

        /**
        if (mConnection.getState() != ImConnection.LOGGED_IN) {
            // connection has been suspended, save the message without send it
            long now = System.currentTimeMillis();
            insertMessageInDb(null, text, now, Imps.MessageType.QUEUED, null, replyId);
            return;
        }**/

        Message msg = new Message(text);
        msg.setID(nextID());

        msg.setFrom(mConnection.getLoginUser().getAddress());

        msg.setType(Imps.MessageType.QUEUED);

        msg.setReplyId(replyId);

        long sendTime = System.currentTimeMillis();

        if (!isResend) {
        //    insertMessageInDb(null, text, sendTime, msg.getType(), 0, msg.getID(), null, replyId);
        }

        int newType = mChatSession.sendMessageAsync(msg, new ChatSessionListener() {

            @Override
            public void onChatSessionCreated(ChatSession session) {

            }

            @Override
            public void onMessageSendSuccess(Message msg, String newMsgId) {

                long sendTime = new Date().getTime();

                if (msg.getDateTime() != null)
                    sendTime = msg.getDateTime().getTime();

                //  deleteMessageInDb(msg.getID());

                updateMessageInDb(msg.getID(), msg.getType(), sendTime, null, newMsgId);

                msg.setID(newMsgId);

               // if (setLastMessage)
                 //   setLastMessage(text, sendTime);

            }

            @Override
            public void onMessageSendFail(Message msg, String newMsgId) {
                long sendTime = new Date().getTime();

                if (msg.getDateTime() != null)
                    sendTime = msg.getDateTime().getTime();

                updateMessageInDb(msg.getID(), msg.getType(), sendTime, null, newMsgId);

                msg.setID(newMsgId);

            }

            @Override
            public void onMessageSendQueued(Message msg, String newMsgId) {
                long sendTime = new Date().getTime();

                if (msg.getDateTime() != null)
                    sendTime = msg.getDateTime().getTime();

                updateMessageInDb(msg.getID(), Imps.MessageType.SENDING, sendTime, null, newMsgId);

                msg.setID(newMsgId);

            }
        });



    }

    private Message storeMediaMessage(String mediaPath, String mimeType, String fileName) {

        String msgBody = mediaPath;
        Message msg = new Message(msgBody);
        msg.setID(nextID());

        msg.setFrom(mConnection.getLoginUser().getAddress());
        msg.setType(Imps.MessageType.QUEUED);

        msg.setContentType(mimeType);

        long sendTime = System.currentTimeMillis();

        insertMessageInDb(null, msgBody, sendTime, msg.getType(), 0, msg.getID(), mimeType, null);
        setLastMessage(msgBody,sendTime);

        return msg;
    }




    @Override
    public boolean offerData(String offerId, final String mediaPath, final String mimeType) {

        if (TextUtils.isEmpty(mimeType))
            return false;

        Uri mediaUri = Uri.parse(mediaPath);

        if (mediaUri == null || mediaUri.getPath() == null)
            return false;

        String fileName = mediaUri.getLastPathSegment();
        InputStream fis = null;
        long fileLength = -1;


        if (mediaUri.getScheme() != null &&
                mediaUri.getScheme().equals("vfs")) {
            info.guardianproject.iocipher.File fileLocal = new info.guardianproject.iocipher.File(mediaUri.getPath());
            if (fileLocal.exists()) {
                try {
                    fis = new info.guardianproject.iocipher.FileInputStream(fileLocal);
                    fileName = fileLocal.getName();
                    fileLength = fileLocal.length();
                } catch (FileNotFoundException fe) {
                    Log.w(TAG, "encrypted file not found on import: " + mediaUri);
                    return false;
                }
            } else {
                Log.w(TAG, "encrypted file not found on import: " + mediaUri);
                return false;
            }
        }
        else if (mediaUri.getScheme() != null &&
                mediaUri.getScheme().equals("content")) {

            ContentResolver cr = service.getContentResolver();

            Cursor returnCursor = cr.query(mediaUri, null, null, null, null);

            if (returnCursor != null && returnCursor.moveToFirst())
            {
                int nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = returnCursor.getColumnIndex(OpenableColumns.SIZE);
                fileName = returnCursor.getString(nameIndex);
                fileLength = returnCursor.getLong(sizeIndex);
                returnCursor.close();
                try {
                    fis = cr.openInputStream(mediaUri);
                }
                catch (Exception e)
                {
                    return false;
                }
            }
            else
            {
                return false;
            }

        }
        else {
            java.io.File fileLocal = new java.io.File(mediaUri.getPath());
            if (fileLocal.exists()) {
                try {
                    fis = new FileInputStream(fileLocal);
                    fileLength = fileLocal.length();
                    fileName = fileLocal.getName();
                } catch (FileNotFoundException fe) {
                    Log.w(TAG, "file system file not found on import: " + mediaUri);
                    return false;
                }
            } else {
                Log.w(TAG, "file system file not found on import: " + mediaUri);
                return false;
            }
        }

        sendMediaMessageAsync(mediaPath, mimeType, fileName, fileLength);


        return true;

    }

    private void sendMediaMessageAsync (final String mediaPath, final String mimeType, final String fileName, final long fileLength)
    {

        //TODO do HTTP Upload XEP 363
        //this is ugly... we need a nice async task!
        new Thread ()
        {

            public void run ()
            {

                final Message msgMedia = storeMediaMessage(mediaPath, mimeType, fileName);

                /**
                UploadProgressListener listener = new UploadProgressListener() {
                    @Override
                    public void onUploadProgress(long sent, long total) {
                        //debug(TAG, "upload complete: " + l + "," + l1);
                        //once this is done, send the message
                        float percentF = ((float)sent)/((float)total);

                        if (mDataHandlerListener != null)
                            mDataHandlerListener.onTransferProgress(true,"","",mediaPath,percentF);
                    }
                };**/

                int newType = mChatSession.sendMessageAsync(msgMedia, new ChatSessionListener() {
                    @Override
                    public void onChatSessionCreated(ChatSession session) {

                    }

                    @Override
                    public void onMessageSendSuccess(Message msg, String newPacketId) {

                        long sendTime = new Date().getTime();

                        if (msg.getDateTime() != null)
                            sendTime = msg.getDateTime().getTime();

                        updateMessageInDb(msg.getID(),msg.getType(),sendTime, null, newPacketId);

                        msg.setID(newPacketId);
                    }

                    @Override
                    public void onMessageSendFail(Message msg, String newPacketId) {
                        long sendTime = new Date().getTime();

                        if (msg.getDateTime() != null)
                            sendTime = msg.getDateTime().getTime();

                        updateMessageInDb(msg.getID(),Imps.MessageType.QUEUED,sendTime, null, newPacketId);

                        msg.setID(newPacketId);
                    }

                    @Override
                    public void onMessageSendQueued(Message msg, String newPacketId) {
                        long sendTime = new Date().getTime();

                        if (msg.getDateTime() != null)
                            sendTime = msg.getDateTime().getTime();

                        updateMessageInDb(msg.getID(),Imps.MessageType.SENDING,sendTime, null, newPacketId);

                        msg.setID(newPacketId);
                    }
                });


                /**
                String resultUrl = mConnection.sendMediaMessage(mChatSession.getParticipant().getAddress().getAddress(), Uri.parse(mediaPath), sendFileName, mimeType, fileLength, doEncryption, listener);

                int newType = Imps.MessageType.OUTGOING_ENCRYPTED;

                if (TextUtils.isEmpty(resultUrl))
                    newType = Imps.MessageType.QUEUED;

                long sendTime = System.currentTimeMillis();
                msgMedia.setBody(resultUrl);
                if (msgMedia.getDateTime() != null)
                    sendTime = msgMedia.getDateTime().getTime();

                updateMessageInDb(msgMedia.getID(),newType,sendTime,mediaPath + ' ' + resultUrl, null);
                **/


                /**
                String mediaPath = localUrl + ' ' + publishUrl;

                long sendTime = System.currentTimeMillis();

                msg.setBody(publishUrl);
                // insertOrUpdateChat(mediaPath);

                int newType = mChatSession.sendMessageAsync(msg, mEnableOmemoGroups);

                if (msg.getDateTime() != null)
                    sendTime = msg.getDateTime().getTime();

                updateMessageInDb(msg.getID(),newType,sendTime,mediaPath);
                */

                //make sure result is valid and starts with https, if so, send it!
                /**
                if (!TextUtils.isEmpty(resultUrl)) {

                    if (Preferences.useProofMode()) {
                        Uri proofUri = Uri.parse(mediaPath + PROOF_FILE_TAG);
                        File fileProof = new info.guardianproject.iocipher.File(proofUri.getPath());
                        if (fileProof.exists()) {
                            String proofUrl = null;
                            try {
                                proofUrl = mConnection.publishFile(sendFileName + ProofMode.PROOF_FILE_TAG, ProofMode.PROOF_MIME_TYPE, fileProof.length(), new info.guardianproject.iocipher.FileInputStream(fileProof), doEncryption, null);
                            } catch (FileNotFoundException e) {
                                e.printStackTrace();
                            }
                            resultUrl += ' ' + proofUrl;
                        }
                    }

                    sendMediaMessage(mediaPath, resultUrl, msgMedia);
                }**/
            }
        }.start();


    }

    /**
     * Sends a message to other participant(s) in this session without adding it
     * to the history.
     *
     *
     */
    /*
    public void sendMessageWithoutHistory(String text) {

     Message msg = new Message(text);
     // TODO OTRCHAT use a lower level method
     mChatSession.sendMessageAsync(msg);
    }*/

    /**
    boolean hasPostponedMessages() {
        String[] projection = new String[] { BaseColumns._ID, Imps.Messages.BODY,
                Imps.Messages.PACKET_ID,
                Imps.Messages.DATE, Imps.Messages.TYPE, Imps.Messages.IS_DELIVERED };
        String selection = Imps.Messages.TYPE + "=?";

        boolean result = false;

        Cursor c = mContentResolver.query(mMessageURI, projection, selection,
                new String[] { Integer.toString(Imps.MessageType.QUEUED) }, null);
        if (c == null) {
            RemoteImService.debug("Query error while querying postponed messages");
            return false;
        }
        else if (c.getCount() > 0)
        {
            result = true;
        }

        c.close();
        return true;

    }**/

    boolean sendingPostponed = false;

    void sendPostponedMessages() {

        if (!sendingPostponed) {
            sendingPostponed = true;

            String[] projection = new String[]{BaseColumns._ID, Imps.Messages.BODY,
                    Imps.Messages.PACKET_ID,
                    Imps.Messages.DATE, Imps.Messages.TYPE, Imps.Messages.IS_DELIVERED};
            String selection = Imps.Messages.TYPE + "=?";

            Cursor c = mContentResolver.query(mMessageURI, projection, selection,
                    new String[]{Integer.toString(Imps.MessageType.QUEUED)}, null);
            if (c == null) {
                RemoteImService.debug("Query error while querying postponed messages");
                return;
            }

            if (c.getCount() > 0) {
                ArrayList<String> messages = new ArrayList<String>();

                while (c.moveToNext())
                    messages.add(c.getString(1));

                removeMessageInDb(Imps.MessageType.QUEUED);

                for (String body : messages) {

                    if (body.startsWith("vfs:/") && (body.split(" ").length == 1))
                    {
                        String offerId = UUID.randomUUID().toString();
                        String mimeType = URLConnection.guessContentTypeFromName(body);
                        if (mimeType != null) {

                            if (mimeType.startsWith("text"))
                                mimeType = "text/plain";

                            offerData(offerId, body, mimeType);
                        }
                    }
                    else {
                        sendMessage(body, false, false, false, null);
                    }
                }
            }

            c.close();

            sendingPostponed = false;
        }
    }

    public void registerChatListener(IChatListener listener) {
        if (listener != null) {
            mRemoteListeners.add(listener);

            if (mDataHandlerListener != null)
                mDataHandlerListener.checkLastTransferRequest ();

            if (mGroup != null)
                mGroup.addMemberListener(mListenerAdapter);
        }
    }

    public void unregisterChatListener(IChatListener listener) {
        if (listener != null) {
            mRemoteListeners.remove(listener);

            if (mGroup != null)
                mGroup.removeMemberListener(mListenerAdapter);
        }
    }

    public void markAsRead() {
        if (mHasUnreadMessages) {

            String baseUsername = mChatSession.getParticipant().getAddress().getBareAddress();
            mStatusBarNotifier.dismissChatNotification(mConnection.getProviderId(), baseUsername);

            mHasUnreadMessages = false;

        }
    }

    public void markAsSeen() {


        mChatSession.setSubscribed(true);
        ((ChatGroup)mChatSession.getParticipant()).setJoined(true);

        Uri uriContact = ContentUris.withAppendedId(Imps.Contacts.CONTENT_URI, mContactId);
        Cursor c = mContentResolver.query(uriContact, new String[]{Imps.Contacts.TYPE}, null, null, null);
        if (c != null) {
            if (c.moveToFirst()) {
                int type = c.getInt(0);
                ContentValues contentValues = new ContentValues();
                contentValues.put(Imps.Contacts.TYPE, type & (~Imps.Contacts.TYPE_FLAG_UNSEEN));
                contentValues.put(Imps.Contacts.SUBSCRIPTION_STATUS,Imps.Contacts.SUBSCRIPTION_STATUS_NONE);
                int rowsUpdated = mContentResolver.update(uriContact, contentValues, null, null);

                if (rowsUpdated == 0)
                {
                    rowsUpdated = mContentResolver.update(uriContact, contentValues, null, null);
                }

                mChatSessionManager.getChatGroupManager().acceptInvitationAsync(getAddress());
            }
            c.close();
        }

    }

    String getNickName(String username) {

        ImEntity participant = mChatSession.getParticipant();
        if (mIsGroupChat) {
            ChatGroup group = (ChatGroup) participant;
            Contact groupMember = group.getMember(username);
            if (groupMember != null && groupMember.getName() != null)
            {
                return groupMember.getName();
            }
            else {
                // group member name might be null!
                String[] parts = username.split(":");
                return parts[0].substring(1);
            }
        } else {
            return ((Contact) participant).getName();
        }
    }

    void onConvertToGroupChatSuccess(ChatGroup group) {
        Contact oldParticipant = (Contact) mChatSession.getParticipant();
        String oldAddress = getAddress();
    //    mChatSession.setParticipant(group);
        mChatSessionManager.updateChatSession(oldAddress, this);

        Uri oldChatUri = mChatURI;
        Uri oldMessageUri = mMessageURI;
        init(group,false);
        //copyHistoryMessages(oldParticipant);

        mContentResolver.delete(oldMessageUri, NON_CHAT_MESSAGE_SELECTION, null);
        mContentResolver.delete(oldChatUri, null, null);

        mListenerAdapter.notifyChatSessionConverted();
        mConvertingToGroupChat = false;
    }

    /**
    private void copyHistoryMessages(Contact oldParticipant) {
        List<org.awesomeapp.info.guardianproject.keanuapp.model.Message> historyMessages = mChatSession.getHistoryMessages();
        int total = historyMessages.size();
        int start = total > MAX_HISTORY_COPY_COUNT ? total - MAX_HISTORY_COPY_COUNT : 0;
        for (int i = start; i < total; i++) {
            org.awesomeapp.info.guardianproject.keanuapp.model.Message msg = historyMessages.get(i);
            boolean incoming = msg.getFrom().equals(oldParticipant.getAddress());
            String contact = incoming ? oldParticipant.getName() : null;
            long time = msg.getDateTime().getTime();
            insertMessageInDb(contact, msg.getBody(), time, incoming ? Imps.MessageType.INCOMING
                                                                    : Imps.MessageType.OUTGOING);
        }
    }*/

    public void setLastMessage (String message)
    {
        setLastMessageForUri(message, System.currentTimeMillis());
    }

    public void setLastMessage (String message, long lastMessageTime)
    {
        setLastMessageForUri(message, lastMessageTime);
    }

    private boolean hasLastMessage ()
    {
        String[] projection = {LAST_UNREAD_MESSAGE};
        Cursor cursor = mContentResolver.query(mChatURI,projection,null,null,null);
        if (cursor != null) {
            boolean hasLast = cursor.getCount() > 0;
            cursor.close();
            return hasLast;
        }

        return false;

    }

    private Uri setLastMessageForUri(String message, long lastMessageTime) {

        ContentValues values = new ContentValues(4);

        values.put(Imps.Chats.LAST_MESSAGE_DATE, lastMessageTime);
        values.put(LAST_UNREAD_MESSAGE, message);
         values.put(Imps.Chats.GROUP_CHAT, mIsGroupChat);
         values.put(Imps.Chats.USE_ENCRYPTION,mUseEncryption);

         int result = mContentResolver.update(mChatURI, values, null, null);

         if (result < 1)
            // ImProvider.insert() will replace the chat if it already exist.
            return mContentResolver.insert(mChatURI, values);
         else
             return mChatURI;
    }

    // Pattern for recognizing a URL, based off RFC 3986
    private static final Pattern urlPattern = Pattern.compile(
            "(?:^|[\\W])((ht|f)tp(s?):\\/\\/)"
                    + "(([\\w\\-]+\\.){1,}?([\\w\\-.~]+\\/?)*"
                    + "[\\p{Alnum}.,%_=?&#\\-+()\\[\\]\\*$~@!:/{};']*)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

    private static final Pattern aesGcmUrlPattern = Pattern.compile(
            "(?:^|[\\W])(aesgcm:\\/\\/)"
                    + "(([\\w\\-]+\\.){1,}?([\\w\\-.~]+\\/?)*"
                    + "[\\p{Alnum}.,%_=?&#\\-+()\\[\\]\\*$~@!:/{};']*)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);


    ArrayList<String> checkForLinkedMedia (String jid, String message, boolean allowWebDownloads)
    {
        ArrayList<String> results = new ArrayList<>();

        Matcher matcher = aesGcmUrlPattern.matcher(message);

        while (matcher.find())
        {
            results.add(matcher.group());
        }

        /**
        if (allowWebDownloads)
        {
            //if someone sends us a random URL, only get it if it is from the same host as the jabberid
            matcher = urlPattern.matcher(message);
            if (matcher.find())
            {
                int matchStart = matcher.start(1);
                int matchEnd = matcher.end();
                String urlDownload = message.substring(matchStart,matchEnd);
                try {
                    String domain = JidCreate.bareFrom(jid).getDomain().toString();

                    //remove the conference subdomain when checking a match to the media upload
                    if (domain.contains("conference."))
                        domain = domain.replace("conference.","");

                    if (urlDownload.contains(domain)) {
                        results.add(urlDownload);
                    }
                }
                catch (XmppStringprepException se)
                {
                    //This shouldn't happeN!
                }
            }
        }**/

        return results;

    }

    private long insertOrUpdateGroupContactInDb(ChatGroup group, boolean isNewSession) {
        // Insert a record in contacts table
        ContentValues values = new ContentValues();
        values.put(Imps.Contacts.USERNAME, group.getAddress().getAddress());

        if (!TextUtils.isEmpty(group.getName()))
            values.put(Imps.Contacts.NICKNAME, group.getName());

        values.put(Imps.Contacts.CONTACTLIST, ContactListManagerAdapter.LOCAL_GROUP_LIST_ID);
        if (isNewSession) {
            values.put(Imps.Contacts.TYPE, Imps.Contacts.TYPE_GROUP | Imps.Contacts.TYPE_FLAG_UNSEEN);
        } else {
            values.put(Imps.Contacts.TYPE, Imps.Contacts.TYPE_GROUP);
        }

        if (group.isJoined())
            values.put(Imps.Contacts.SUBSCRIPTION_STATUS,Imps.Contacts.SUBSCRIPTION_STATUS_NONE);
        else
            values.put(Imps.Contacts.SUBSCRIPTION_STATUS,Imps.Contacts.SUBSCRIPTION_STATUS_SUBSCRIBE_PENDING);


        ContactListManagerAdapter listManager = (ContactListManagerAdapter) mConnection
                .getContactListManager();
        
        long id = listManager.queryGroup(group);
        
        if (id == -1)
        {
            Uri contactUri = ContentUris.withAppendedId(
                    ContentUris.withAppendedId(Imps.Contacts.CONTENT_URI, mConnection.mProviderId),
                    mConnection.mAccountId);

            id = ContentUris.parseId(mContentResolver.insert(contactUri, values));
        }
        else
        {
            Uri uriContact = ContentUris.withAppendedId(Imps.Contacts.CONTENT_URI, mContactId);
            mContentResolver.update(uriContact, values, null, null);
        }

        for (Contact member : group.getMembers()) {
            insertGroupMemberInDb(member);
        }

        return id;
    }

    boolean insertGroupMemberInDb(Contact member) {
        String username = member.getAddress().getAddress();
        String nickname = member.getName();
        return insertOrUpdateGroupMemberInDb(username, nickname, member);
    }

    boolean insertOrUpdateGroupMemberInDb(String oldUsername, String oldNickname, Contact member) {

        if (mChatURI != null) {
            String username = member.getAddress().getAddress();
            String nickname = member.getName();

            ContentValues values = new ContentValues(4);
            values.put(Imps.GroupMembers.USERNAME, username);

            if (!TextUtils.isEmpty(nickname))
                values.put(Imps.GroupMembers.NICKNAME, nickname);

            long groupId = ContentUris.parseId(mChatURI);
            Uri uri = ContentUris.withAppendedId(Imps.GroupMembers.CONTENT_URI, groupId);

            Map.Entry<String, String[]> whereEntry = getGroupMemberWhereClause(oldUsername, oldNickname);

            long databaseId = 0;
            Cursor c = mContentResolver.query(uri, new String[] { "_id" }, whereEntry.getKey(), whereEntry.getValue() , null);
            if (c != null) {
                if (c.moveToFirst()) {
                    databaseId = c.getLong(c.getColumnIndex("_id"));
                }
                c.close();
            }
            if (databaseId > 0) {
                mContentResolver.update(uri, values, "_id=?", new String[] { String.valueOf(databaseId) });
                return false;
            } else {
                values.put(Imps.GroupMembers.ROLE, "none");
                values.put(Imps.GroupMembers.AFFILIATION, "none");
                mContentResolver.insert(uri, values);
                return true;
            }
        }

        return false;
    }

    void updateGroupMemberRoleAndAffiliationInDb(Contact member, String role, String affiliation) {
        if (mChatURI != null && (role != null || affiliation != null)) {
            String username = member.getAddress().getAddress();
            String nickname = member.getName();

            ContentValues values = new ContentValues(4);
            if (role != null) {
                values.put(Imps.GroupMembers.ROLE, role);
            }
            if (affiliation != null) {
                values.put(Imps.GroupMembers.AFFILIATION, affiliation);
            }

            long groupId = ContentUris.parseId(mChatURI);
            Uri uri = ContentUris.withAppendedId(Imps.GroupMembers.CONTENT_URI, groupId);

            Map.Entry<String, String[]> whereEntry = getGroupMemberWhereClause(username, nickname);
            mContentResolver.update(uri, values, whereEntry.getKey(), whereEntry.getValue());
        }
    }

    // When updating the member list, first mark all members, admins and owners with an affiliation
    // of "transient". Then do the update. After the update, delete all members with affiliation
    // "transient". This will ensure that stale entries are removed.
    void beginGroupMemberUpdates(ChatGroup group) {
        if (mChatURI != null) {
            ContentValues values = new ContentValues(1);
            values.put(Imps.GroupMembers.AFFILIATION, "transient");

            long groupId = ContentUris.parseId(mChatURI);
            Uri uri = ContentUris.withAppendedId(Imps.GroupMembers.CONTENT_URI, groupId);
            mContentResolver.update(uri, values, Imps.GroupMembers.GROUP + "=? AND (" +
                    Imps.GroupMembers.AFFILIATION + "=?" + " OR " +
                    Imps.GroupMembers.AFFILIATION + "=?" + " OR " +
                    Imps.GroupMembers.AFFILIATION + "=?)", new String[]{
                    String.valueOf(groupId), "member", "admin", "owner"
            });
        }
    }

    void endGroupMemberUpdates(ChatGroup group) {
        if (mChatURI != null) {
            long groupId = ContentUris.parseId(mChatURI);
            Uri uri = ContentUris.withAppendedId(Imps.GroupMembers.CONTENT_URI, groupId);
            mContentResolver.delete(uri,Imps.GroupMembers.GROUP + "=? AND " +
                    Imps.GroupMembers.AFFILIATION + "=?", new String[]{
                    String.valueOf(groupId), "transient"
            });
        }
    }

    void deleteAllGroupMembers() {

        if (mChatURI != null) {
            long groupId = ContentUris.parseId(mChatURI);
            Uri uri = ContentUris.withAppendedId(Imps.GroupMembers.CONTENT_URI, groupId);
            mContentResolver.delete(uri, null, null);
        }
        //  insertMessageInDb(member.getName(), null, System.currentTimeMillis(),
        //    Imps.MessageType.PRESENCE_UNAVAILABLE);
    }

    Map.Entry<String, String[]> getGroupMemberWhereClause(String username, String nickname) {
        String whereClause = "";
        ArrayList<String> selection = new ArrayList<>();
        if (!TextUtils.isEmpty(nickname)) {
            whereClause += Imps.GroupMembers.NICKNAME + "=?";
            selection.add(nickname);
        }
        if (!TextUtils.isEmpty(username)) {
            if (whereClause.length() > 0) {
                whereClause += " OR ";
            }
            whereClause += Imps.GroupMembers.USERNAME + "=?";
            selection.add(username);
        }
        long groupId = ContentUris.parseId(mChatURI);
        if (whereClause.length() > 0) {
            whereClause = "(" + whereClause + ") AND " + Imps.GroupMembers.GROUP + "=?";
        } else {
            whereClause = Imps.GroupMembers.GROUP + "=?";
        }
        selection.add(String.valueOf(groupId));
        return new AbstractMap.SimpleEntry<String, String[]>(whereClause, selection.toArray(new String[0]));
    }

    void deleteGroupMemberInDb(Contact member) {
        if (mChatURI != null) {
            String username = member.getAddress().getAddress();
            String nickname = member.getName();

            long groupId = ContentUris.parseId(mChatURI);
            Uri uri = ContentUris.withAppendedId(Imps.GroupMembers.CONTENT_URI, groupId);
            Map.Entry<String, String[]> entry = getGroupMemberWhereClause(username, nickname);
            mContentResolver.delete(uri, entry.getKey(), entry.getValue());
        }
      //  insertMessageInDb(member.getName(), null, System.currentTimeMillis(),
            //    Imps.MessageType.PRESENCE_UNAVAILABLE);
    }

    void insertPresenceUpdatesMsg(String contact, Presence presence) {
        int status = presence.getStatus();

        Integer previousStatus = mContactStatusMap.get(contact);
        if (previousStatus != null && previousStatus == status) {
            // don't insert the presence message if it's the same status
            // with the previous presence update notification
            return;
        }

        mContactStatusMap.put(contact, status);
        int messageType;
        switch (status) {
        case Presence.AVAILABLE:
            messageType = Imps.MessageType.PRESENCE_AVAILABLE;
            break;

        case Presence.AWAY:
        case Presence.IDLE:
            messageType = Imps.MessageType.PRESENCE_AWAY;
            break;

        case Presence.DO_NOT_DISTURB:
            messageType = Imps.MessageType.PRESENCE_DND;
            break;

        default:
            messageType = Imps.MessageType.PRESENCE_UNAVAILABLE;
            break;
        }

        if (mIsGroupChat) {
            insertMessageInDb(contact, null, System.currentTimeMillis(), messageType, null, null);
        } else {
            insertMessageInDb(null, null, System.currentTimeMillis(), messageType, null, null);
        }
    }

    void removeMessageInDb(int type) {
        mContentResolver.delete(mMessageURI, Imps.Messages.TYPE + "=?",
                new String[] { Integer.toString(type) });
    }

    Uri insertMessageInDb(String contact, String body, long time, int type, String mimeType, String replyId) {
        return insertMessageInDb(contact, body, time, type, 0/*No error*/, nextID(), mimeType, replyId);
    }

    /**
     * A prefix helps to make sure that ID's are unique across mutliple instances.
     */
    private static String prefix = UUID.randomUUID().toString().substring(0,6) + "-";

    /**
     * Keeps track of the current increment, which is appended to the prefix to
     * forum a unique ID.
     */
    private static long id = 0;

    static String nextID ()
    {
        return prefix + Long.toString(id++);
    }

    public boolean doesMessageExist (String msgId, String body)
    {
        return Imps.messageExists(mContentResolver,msgId,-1,body);
    }

    Uri insertMessageInDb(String contact, String body, long time, int type, int errCode, String id, String mimeType, String replyId) {
        return Imps.insertMessageInDb(mContentResolver, mIsGroupChat, mContactId, false, contact, body, time, type, errCode, id, mimeType, replyId);
    }

    int updateMessageInDb(String packetId, int type, long time, String body, String newPacketId) {

        return Imps.updateMessageInDb(mContentResolver, packetId, type, time, mContactId, body, newPacketId);
    }

    int deleteMessageInDb (String packetId) {

        return mContentResolver.delete(mMessageURI, Imps.Messages.PACKET_ID + "=?",
                new String[] { packetId });

    }


    class ListenerAdapter implements MessageListener, GroupMemberListener {

        public void onMessageRedacted (String msgId)
        {
            Imps.updateMessageInDb(mContentResolver,msgId,-1,-1,-1,service.getString(R.string.message_deleted),null);
        }

        public boolean onIncomingMessage(ChatSession ses, final Message msg, boolean notifyUser) {

            String body = msg.getBody();
       //     String username = msg.getFrom().getAddress();
            String bareUsername = msg.getFrom().getAddress();
            String nickname = getNickName(bareUsername);

            long time = msg.getDateTime().getTime();

            String notificationText = body;

            boolean wasMessageSeen = false;

            if ((!TextUtils.isEmpty(msg.getContentType()))
                    && (!msg.getContentType().startsWith("text")) ) {

                String displayType = "\uD83D\uDDCE";

                //update the notificati
                if (msg.getContentType().contains("audio"))
                {
                    displayType += "🔊\uD83D\uDD0A";
                }
                else if (msg.getContentType().contains("image"))
                {
                    displayType += "\uD83D\uDCF7";
                }
                else if (msg.getContentType().contains("video"))
                {
                    displayType += "\u25B6";
                }

                notificationText = service.getString(R.string.file_notify_text, displayType, nickname);

            }


            //if it wasn't a media file or we had an issue downloading, then it is chat
            Uri messageUri = null;


            int msgType = msg.getType();

            if (Imps.messageExists(mContentResolver,msg.getID(),-1,null)) {

                //update the message body
                if (!Imps.messageExists(mContentResolver,msg.getID(),-1,body))
                    updateMessageInDb(msg.getID(),msgType,-1,body,msg.getID());

                return false;
            }
            else {
                if (msg.getID() == null)
                    messageUri = insertMessageInDb(bareUsername, body, time, msgType, msg.getContentType(), msg.getReplyId());
                else
                    messageUri = insertMessageInDb(bareUsername, body, time, msgType, 0, msg.getID(), msg.getContentType(), msg.getReplyId());
            }

            if (!msg.isReaction())
                setLastMessage(body, time);

            if (messageUri == null) {
                Log.e(TAG,"error saving message to the db: " + msg.getID());
                return false; //couldn't write to database
            }


            try {

              //  int N = mRemoteListeners.beginBroadcast();
                for (int i = 0; i < mRemoteListeners.size(); i++) {
                    IChatListener listener = mRemoteListeners.get(i);
                    try {
                        wasMessageSeen = listener.onIncomingMessage(ChatSessionAdapter.this, msg);
                    } catch (RemoteException e) {
                        // The RemoteCallbackList will take care of removing the
                        // dead listeners.
                    }
                }

            } catch (Exception e) {
                Log.w(TAG, "error notifying of new messages", e);

            }
            finally{
             // mRemoteListeners.finishBroadcast();
            }




            // Due to the move to fragments, we could have listeners for ChatViews that are not visible on the screen.
            // This is for fragments adjacent to the current one.  Therefore we can't use the existence of listeners
            // as a filter on notifications.
            if ((!wasMessageSeen) && notifyUser && (!msg.isReaction())) {

                if (isGroupChatSession()) {
                    if (!isMuted()) {
                        ChatGroup group = (ChatGroup) ses.getParticipant();
                        try {
                            Contact contact = mConnection.getContactListManager().getContactByAddress(nickname);
                            if (contact != null) {
                                nickname = contact.getName();

                                if (!TextUtils.isEmpty(nickname))
                                    nickname = nickname.split("@")[0];
                            }

                        } catch (Exception e) {
                        }

                        mStatusBarNotifier.notifyGroupChat(mConnection.getProviderId(), mConnection.getAccountId(),
                                getId(), group.getAddress().getBareAddress(), group.getName(), nickname, notificationText, false);
                    }
                } else {

                    //reinstated body display here in the notification; perhaps add preferences to turn that off
                    mStatusBarNotifier.notifyChat(mConnection.getProviderId(), mConnection.getAccountId(),
                            getId(), bareUsername, nickname, notificationText, false);

                }
            }


            mHasUnreadMessages = true;
            return true;
        }



        public void onSendMessageError(ChatSession ses, final Message msg, final ImErrorInfo error) {
            insertMessageInDb(null, null, System.currentTimeMillis(), Imps.MessageType.OUTGOING,
                    error.getCode(), null, null, msg.getReplyId());

            try {
            //    final int N = mRemoteListeners.beginBroadcast();
                for (int i = 0; i < mRemoteListeners.size(); i++) {
                    IChatListener listener = mRemoteListeners.get(i);
                    try {
                        listener.onSendMessageError(ChatSessionAdapter.this, msg, error);
                    } catch (RemoteException e) {
                        // The RemoteCallbackList will take care of removing the
                        // dead listeners.
                    }
                }
             //   mRemoteListeners.finishBroadcast();
            }
            catch (Exception e){}
        }

        public void onSubjectChanged(ChatGroup group, String subject)
        {
            if (mChatURI != null) {
                ContentValues values1 = new ContentValues(1);
                values1.put(Imps.Contacts.NICKNAME,subject);
                ContentValues values = values1;

                Uri uriContact = ContentUris.withAppendedId(Imps.Contacts.CONTENT_URI, mContactId);
                mContentResolver.update(uriContact, values, null, null);

                try {

                  //  final int N = mRemoteListeners.beginBroadcast();
                    for (int i = 0; i < mRemoteListeners.size(); i++) {
                        IChatListener listener = mRemoteListeners.get(i);
                        try {
                            listener.onGroupSubjectChanged(ChatSessionAdapter.this);
                        } catch (RemoteException e) {
                            // The RemoteCallbackList will take care of removing the
                            // dead listeners.
                        }
                    }
               //     mRemoteListeners.finishBroadcast();

                }
                catch (Exception e){}
            }
        }

        public void onMembersReset () {
            deleteAllGroupMembers();
        }

        public void onMemberJoined(ChatGroup group, final Contact contact) {
            boolean isNew = insertGroupMemberInDb(contact);

            if (isNew) {
                //add presence joined
                Presence p = new Presence(Presence.AVAILABLE);
                insertPresenceUpdatesMsg(contact.getAddress().getAddress(), p);

                try {
                    //   final int N = mRemoteListeners.beginBroadcast();
                    for (int i = 0; i < mRemoteListeners.size(); i++) {
                        IChatListener listener = mRemoteListeners.get(i);
                        try {
                            listener.onContactJoined(ChatSessionAdapter.this, contact);
                        } catch (RemoteException e) {
                            // The RemoteCallbackList will take care of removing the
                            // dead listeners.
                        }
                    }
                    //   mRemoteListeners.finishBroadcast();
                } catch (Exception e) {
                }
            }
        }

        @Override
        public void onMemberRoleChanged(ChatGroup chatGroup, Contact contact, String role, String affiliation) {
            updateGroupMemberRoleAndAffiliationInDb(contact, role, affiliation);
            try {
            //    final int N = mRemoteListeners.beginBroadcast();
                for (int i = 0; i < mRemoteListeners.size(); i++) {
                    IChatListener listener = mRemoteListeners.get(i);
                    try {
                        listener.onContactRoleChanged(ChatSessionAdapter.this, contact);
                    } catch (RemoteException e) {
                        // The RemoteCallbackList will take care of removing the
                        // dead listeners.
                    }
                }
               // mRemoteListeners.finishBroadcast();
            }
            catch (Exception e){}
        }

        public void onMemberLeft(ChatGroup group, final Contact contact) {
            deleteGroupMemberInDb(contact);

            try {
            //    final int N = mRemoteListeners.beginBroadcast();
                for (int i = 0; i < mRemoteListeners.size(); i++) {
                    IChatListener listener = mRemoteListeners.get(i);
                    try {
                        listener.onContactLeft(ChatSessionAdapter.this, contact);
                    } catch (RemoteException e) {
                        // The RemoteCallbackList will take care of removing the
                        // dead listeners.
                    }
                }
               // mRemoteListeners.finishBroadcast();
            }
            catch (Exception e){}
        }

        public void onError(ChatGroup group, final ImErrorInfo error) {
            // TODO: insert an error message?
            try {
                //final int N = mRemoteListeners.beginBroadcast();
                for (int i = 0; i < mRemoteListeners.size(); i++) {
                    IChatListener listener = mRemoteListeners.get(i);
                    try {
                        listener.onInviteError(ChatSessionAdapter.this, error);
                    } catch (RemoteException e) {
                        // The RemoteCallbackList will take care of removing the
                        // dead listeners.
                    }
                }
               // mRemoteListeners.finishBroadcast();
            }
            catch (Exception e){}
        }

        public void notifyChatSessionConverted() {
            try {
               // final int N = mRemoteListeners.beginBroadcast();
                for (int i = 0; i < mRemoteListeners.size(); i++) {
                    IChatListener listener = mRemoteListeners.get(i);
                    try {
                        listener.onConvertedToGroupChat(ChatSessionAdapter.this);
                    } catch (RemoteException e) {
                        // The RemoteCallbackList will take care of removing the
                        // dead listeners.
                    }
                }
             //   mRemoteListeners.finishBroadcast();
            }
            catch (Exception e){}
        }

        @Override
        public void onIncomingReceipt(ChatSession ses, String id, boolean wasEncrypted) {
            Imps.updateConfirmInDb(mContentResolver, mContactId, id, true, wasEncrypted);
        }

        @Override
        public void onIncomingReadMarker(ChatSession ses, String id, boolean wasEncrypted) {
            Imps.updateAllConfirmInDb(mContentResolver, mContactId, id, true, wasEncrypted);
        }

        @Override
        public void onMessagePostponed(ChatSession ses, String id) {
            updateMessageInDb(id, Imps.MessageType.QUEUED, -1, null, null);
        }

        @Override
        public void onReceiptsExpected(ChatSession ses, boolean isExpected) {
            // TODO

        }


        @Override
        public void onBeginMemberUpdates(ChatGroup group) {
            beginGroupMemberUpdates(group);
            try {
               // final int N = mRemoteListeners.beginBroadcast();
                for (int i = 0; i < mRemoteListeners.size(); i++) {
                    IChatListener listener = mRemoteListeners.get(i);
                    try {
                        listener.onBeginMemberListUpdate(ChatSessionAdapter.this);
                    } catch (RemoteException e) {
                        // The RemoteCallbackList will take care of removing the
                        // dead listeners.
                    }
                }
             //   mRemoteListeners.finishBroadcast();

            }
            catch (Exception e){}
        }

        @Override
        public void onEndMemberUpdates(ChatGroup group) {
            endGroupMemberUpdates(group);
            try {

           // final int N = mRemoteListeners.beginBroadcast();
                for (int i = 0; i < mRemoteListeners.size(); i++) {
                IChatListener listener = mRemoteListeners.get(i);
                try {
                    listener.onEndMemberListUpdate(ChatSessionAdapter.this);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.
                }
            }
          //  mRemoteListeners.finishBroadcast();

            }
            catch (Exception e){}
        }
    }

    class ChatConvertor implements GroupListener, GroupMemberListener {
        private ChatGroupManager mGroupMgr;
        private String mGroupName;

        public ChatConvertor() {
            mGroupMgr = mConnection.mGroupManager;
        }

        public void convertToGroupChat(String nickname) {
            mGroupMgr.addGroupListener(this);
            mGroupName = "G" + System.currentTimeMillis();
            try
            {
                mGroupMgr.createChatGroupAsync(mGroupName, false, true, true, new IChatSessionListener() {
                    @Override
                    public void onChatSessionCreated(IChatSession session) throws RemoteException {

                    }

                    @Override
                    public void onChatSessionCreateError(String name, ImErrorInfo error) throws RemoteException {

                    }

                    @Override
                    public IBinder asBinder() {
                        return null;
                    }
                });
            }
            catch (Exception e){
                e.printStackTrace();
            }
        }

        public void onGroupCreated(ChatGroup group) {
            if (mGroupName.equalsIgnoreCase(group.getName())) {
                mGroupMgr.removeGroupListener(this);
                group.addMemberListener(this);
                mGroupMgr.inviteUserAsync(group, (Contact) mChatSession.getParticipant());
            }
        }

        @Override
        public void onMembersReset() {
        }

        public void onMemberJoined(ChatGroup group, Contact contact) {
            if (mChatSession.getParticipant().equals(contact)) {
                onConvertToGroupChatSuccess(group);
            }

            mContactStatusMap.put(contact.getName(), contact.getPresence().getStatus());
        }

        @Override
        public void onMemberRoleChanged(ChatGroup chatGroup, Contact contact, String role, String affiliation) {

        }

        public void onSubjectChanged(ChatGroup group, String subject){}

        public void onGroupDeleted(ChatGroup group) {
        }

        public void onGroupError(int errorType, String groupName, ImErrorInfo error) {
        }

        public void onJoinedGroup(ChatGroup group) {
        }

        public void onLeftGroup(ChatGroup group) {
        }

        public void onError(ChatGroup group, ImErrorInfo error) {
        }

        public void onMemberLeft(ChatGroup group, Contact contact) {
            mContactStatusMap.remove(contact.getName());
        }

        @Override
        public void onBeginMemberUpdates(ChatGroup group) {

        }

        @Override
        public void onEndMemberUpdates(ChatGroup group) {

        }
    }

    @Override
    public void setDataListener(IDataListener dataListener) throws RemoteException {

        mDataListener = dataListener;
    }

    @Override
    public void setIncomingFileResponse (String transferForm, boolean acceptThis, boolean acceptAll)
    {

        mAcceptTransfer = acceptThis;
        mAcceptAllTransfer = acceptAll;
        mWaitingForResponse = false;


    }

    class DataHandlerListenerImpl extends IDataListener.Stub {

        @Override
        public void onTransferComplete(boolean outgoing, String offerId, String from, String url, String mimeType, String filePath) {


            try {


                if (outgoing) {
                    Imps.updateConfirmInDb(service.getContentResolver(), mContactId, offerId, true, true);
                } else {

                    try
                    {
                        int type = Imps.MessageType.INCOMING_ENCRYPTED;

                        setLastMessage(filePath);

                        Uri messageUri = Imps.insertMessageInDb(service.getContentResolver(),
                                mIsGroupChat, getId(),
                                true, from,
                                filePath, System.currentTimeMillis(), type,
                                0, offerId, mimeType, null);

                        int percent = (int)(100);

                        String[] path = url.split("/");
                        String sanitizedPath = SystemServices.sanitize(path[path.length - 1]);

                        int N = 0;

                        try {
                       //     N = mRemoteListeners.beginBroadcast();
                            for (int i = 0; i < mRemoteListeners.size(); i++) {
                                IChatListener listener = mRemoteListeners.get(i);
                                try {
                                    listener.onIncomingFileTransferProgress(sanitizedPath, percent);
                                } catch (RemoteException e) {
                                    // The RemoteCallbackList will take care of removing the
                                    // dead listeners.
                                }
                            }
                            //mRemoteListeners.finishBroadcast();
                        }
                        catch (Exception e){}

                        if (N == 0) {
                            String nickname = getNickName(from);
                            if (!isMuted())
                                mStatusBarNotifier.notifyChat(mConnection.getProviderId(), mConnection.getAccountId(),
                                    getId(), from, nickname,service.getString(R.string.file_notify_text,mimeType) , false);
                        }
                    }
                    catch (Exception e)
                    {
                        Log.e(LOG_TAG,"Error updating file transfer progress",e);
                    }

                }

            } catch (Exception e) {
             //   mHandler.showAlert(service.getString(R.string.error_chat_file_transfer_title), service.getString(R.string.error_chat_file_transfer_body));
            //    OtrDebugLogger.log("error reading file", e);
            }


        }

        @Override
        public void onTransferFailed(boolean outgoing, String offerId, String from, String url, String reason) {


            String[] path = url.split("/");
            String sanitizedPath = SystemServices.sanitize(path[path.length - 1]);

            try {
              //  final int N = mRemoteListeners.beginBroadcast();
                for (int i = 0; i < mRemoteListeners.size(); i++) {
                    IChatListener listener = mRemoteListeners.get(i);
                    try {
                        listener.onIncomingFileTransferError(sanitizedPath, reason);
                    } catch (RemoteException e) {
                        // The RemoteCallbackList will take care of removing the
                        // dead listeners.
                    }
                }
             //   mRemoteListeners.finishBroadcast();
            }
            catch (Exception e){}
        }

        @Override
        public void onTransferProgress(boolean outgoing, String offerId, String from, String url, float percentF) {

            int percent = (int)(100*percentF);

            String[] path = url.split("/");
            String sanitizedPath = SystemServices.sanitize(path[path.length - 1]);

            try
            {
               // final int N = mRemoteListeners.beginBroadcast();
                for (int i = 0; i < mRemoteListeners.size(); i++) {
                    IChatListener listener = mRemoteListeners.get(i);
                    try {
                        listener.onIncomingFileTransferProgress(sanitizedPath, percent);
                    } catch (RemoteException e) {
                        // The RemoteCallbackList will take care of removing the
                        // dead listeners.
                    }
                }
            }
            catch (Exception e)
            {
                Log.w(LOG_TAG,"error broadcasting progress: " + e);
            }
            finally
            {
               // mRemoteListeners.finishBroadcast();
            }
        }


        private String mLastTransferFrom;
        private String mLastTransferUrl;

        public void checkLastTransferRequest ()
        {
            if (mLastTransferFrom != null)
            {
                onTransferRequested(mLastTransferUrl,mLastTransferFrom,mLastTransferFrom,mLastTransferUrl);
                mLastTransferFrom = null;
                mLastTransferUrl = null;
            }
        }

        @Override
        public synchronized boolean onTransferRequested(String offerId, String from, String to, String transferUrl) {

            mAcceptTransfer = false;
            mWaitingForResponse = true;
            mLastFileUrl = transferUrl;

            if (mAcceptAllTransfer)
            {
                mAcceptTransfer = true;
                mWaitingForResponse = false;
                mLastTransferFrom = from;
                mLastTransferUrl = transferUrl;

              //  mDataHandler.acceptTransfer(mLastFileUrl, from);
            }
            else
            {
                try
                {
                 //   final int N = mRemoteListeners.beginBroadcast();

                    if (mRemoteListeners.size() > 0)
                    {
                        for (int i = 0; i < mRemoteListeners.size(); i++) {
                            IChatListener listener = mRemoteListeners.get(i);
                            try {
                                listener.onIncomingFileTransfer(from, transferUrl);
                            } catch (RemoteException e) {
                                // The RemoteCallbackList will take care of removing the
                                // dead listeners.
                            }
                        }
                    }
                    else
                    {
                        mLastTransferFrom = from;
                        mLastTransferUrl = transferUrl;
                        String nickname = getNickName(from);

                        //reinstated body display here in the notification; perhaps add preferences to turn that off
                        if (!isMuted())
                            mStatusBarNotifier.notifyChat(mConnection.getProviderId(), mConnection.getAccountId(),
                                getId(), from, nickname, "Incoming file request", false);
                    }
                }
                finally
                {
                   // mRemoteListeners.finishBroadcast();
                }

                mAcceptTransfer = false; //for now, wait for the user callback
            }

            return mAcceptTransfer;

        }



    }

    public synchronized void setContactTyping (Contact contact, boolean isTyping)
    {
        try {
           // int N = mRemoteListeners.beginBroadcast();
            for (int i = 0; i < mRemoteListeners.size(); i++) {
                IChatListener listener = mRemoteListeners.get(i);
                try {
                    listener.onContactTyping(ChatSessionAdapter.this, contact, isTyping);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.
                }
            }
        //    mRemoteListeners.finishBroadcast();
        }
        catch (IllegalStateException ise)
        {
            //sometimes this broadcast overlaps with others
        }
    }

    public void sendTypingStatus (boolean isTyping)
    {
       // mConnection.sendTypingStatus("fpp", isTyping);
    }


    public String getPublicAddress ()
    {
        return mChatSession.getPublicAddress();
    }

    public void setPublic (boolean isPublic)
    {
        mChatSession.setPublic(isPublic);
    }

    public boolean useEncryption (boolean useEncryption)
    {
        updateEncryptionState(useEncryption);
        mChatSession.setUseEncryption(useEncryption);
        return getUseEncryption();
    }

    public void updateEncryptionState (boolean useEncryption)
    {
        mUseEncryption = useEncryption;
        ContentValues values = new ContentValues();
        values.put(Imps.Chats.USE_ENCRYPTION,useEncryption ? 1 : 0);
        int rowsUpdate = mContentResolver.update(mChatURI,values,null,null);
    }

    public boolean getUseEncryption ()
    {
        return mUseEncryption;
    }

    public boolean isEncrypted ()
    {
        return mChatSession.canEncrypt();

    }

    @Override
    public void setGroupChatSubject(String subject, boolean updateRemote) throws RemoteException {
        try {
            if (isGroupChatSession()) {
                ChatGroup group = (ChatGroup)mChatSession.getParticipant();
                group.setName(subject);

                if (updateRemote)
                    getGroupManager().setGroupSubject(group, subject);

                //update the database
                ContentValues values = new ContentValues(1);
                values.put(Imps.Contacts.NICKNAME,subject);

                Uri uriContact = ContentUris.withAppendedId(Imps.Contacts.CONTENT_URI, mContactId);
                mContentResolver.update(uriContact, values, null, null);

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    @Override
    public void refreshContactFromServer() throws RemoteException {
        try {
            if (isGroupChatSession()) {
                ChatGroup group = (ChatGroup)mChatSession.getParticipant();
                if (group != null) {

                    ChatGroupManager mGroupMan = mConnection.getAdaptee().getChatGroupManager();
                    mGroupMan.refreshGroup(group);
                }
            }

            syncMessages();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void syncMessages() throws RemoteException {
        mConnection.getAdaptee().syncMessages(mChatSession);

    }


    @Override
    public void refreshMessage(String msgId) throws RemoteException {
        mConnection.getAdaptee().refreshMessage(mChatSession, msgId);
    }

    @Override
    public void checkReceipt(String msgId) throws RemoteException {
        mConnection.getAdaptee().refreshMessage(mChatSession, msgId);
    }

    @Override
    public List<Contact> getGroupChatOwners ()
    {
        try {
            if (isGroupChatSession()) {
                ChatGroup group = (ChatGroup)mChatSession.getParticipant();
                if (group != null) {
                    return group.getOwners();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    @Override
    public List<Contact> getGroupChatAdmins ()
    {
        try {
            if (isGroupChatSession()) {
                ChatGroup group = (ChatGroup)mChatSession.getParticipant();
                if (group != null) {
                    return group.getAdmins();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    public void setMuted (boolean muted)
    {
        int newChatType = muted ? Imps.ChatsColumns.CHAT_TYPE_MUTED : Imps.ChatsColumns.CHAT_TYPE_ACTIVE;
        ContentValues values = new ContentValues();
        values.put(Imps.Chats.CHAT_TYPE,newChatType);
        mContentResolver.update(mChatURI,values,null,null);
        mIsMuted = muted;
    }

    public boolean isMuted() {
        return mIsMuted;
    }

    private void initMuted () {
        int type = Imps.ChatsColumns.CHAT_TYPE_ACTIVE;

        String[] projection = {Imps.Chats.CHAT_TYPE};
        Cursor c = mContentResolver.query(mChatURI, projection, null, null, null);
        if (c != null) {
            if (c.moveToFirst()) {
                type = c.getInt(0);
            }
            c.close();
        }
        mIsMuted = type == Imps.ChatsColumns.CHAT_TYPE_MUTED;
    }

    private void initUseEncryption () {

        String[] projection = {Imps.Chats.USE_ENCRYPTION};
        Cursor c = mContentResolver.query(mChatURI, projection, null, null, null);
        if (c != null) {
            if (c.moveToFirst()) {
                mUseEncryption = c.getInt(0) > 0;
            }
            c.close();
        }



    }

    public String downloadMedia (String mediaLink, String msgId)
    {
        return downloadMedia(mediaLink, msgId, mNickname);
    }

    public String downloadMedia (String mediaLink, String msgId, String nickname)
    {
        String mimeType = null;

        try {
            /**
            Downloader dl = new Downloader();
            File fileDownload = dl.openSecureStorageFile(mContactId + "", mediaLink);
            OutputStream storageStream = new info.guardianproject.iocipher.FileOutputStream(fileDownload);
            boolean downloaded = dl.get(mediaLink, storageStream);

            if (downloaded) {
                mimeType = dl.getMimeType();

                try {
                    //boolean isVerified = getDefaultOtrChatSession().isKeyVerified(bareUsername);
                    //int type = isVerified ? Imps.MessageType.INCOMING_ENCRYPTED_VERIFIED : Imps.MessageType.INCOMING_ENCRYPTED;
                    int type = Imps.MessageType.INCOMING;
                    if (mediaLink.startsWith("aesgcm"))
                        type = Imps.MessageType.INCOMING_ENCRYPTED_VERIFIED;

                    String result = SecureMediaStore.vfsUri(fileDownload.getAbsolutePath()).toString();

                    setLastMessage(result);

                    Uri messageUri = Imps.insertMessageInDb(service.getContentResolver(),
                            mIsGroupChat, getId(),
                            true, nickname,
                            result, System.currentTimeMillis(), type,
                            0, msgId, mimeType);

                    if (messageUri == null) //error writing to database
                    {
                        Log.e(TAG,"error saving message to the db: " + msgId);

                        return null;
                    }

                    String sanitizedPath = Uri.parse(mediaLink).getLastPathSegment();

                    try {
                        int N = mRemoteListeners.beginBroadcast();
                        for (int i = 0; i < N; i++) {
                            IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                            try {
                                listener.onIncomingFileTransferProgress(sanitizedPath, 100);
                            } catch (RemoteException e) {
                                // The RemoteCallbackList will take care of removing the
                                // dead listeners.
                            }
                        }
                        mRemoteListeners.finishBroadcast();
                    } catch (Exception e) {
                        Log.e(TAG,"error notifying of new messages",e);

                    }

                } catch (Exception e) {
                    Log.e(ImApp.LOG_TAG, "Error updating file transfer progress", e);
                }


            }**/

        } catch (Exception e) {
            Log.e(LOG_TAG, "error downloading incoming media", e);

        }

        return mimeType;
    }

    @Override
    public void kickContact(String contactString) {
        if (!mIsGroupChat) {
            return;
        }
        Contact contact = new Contact(new BaseAddress(contactString), contactString, Imps.Contacts.TYPE_NORMAL);
        getGroupManager().removeGroupMemberAsync((ChatGroup) mChatSession.getParticipant(), contact);
    }

    @Override
    public void grantAdmin(String contactString) {
        if (!mIsGroupChat) {
            return;
        }
        Contact contact = new Contact(new BaseAddress(contactString), contactString, Imps.Contacts.TYPE_NORMAL);
        getGroupManager().grantAdminAsync((ChatGroup) mChatSession.getParticipant(), contact);
    }

    private int getMemberCount () {
        String[] projection = {Imps.GroupMembers.USERNAME, Imps.GroupMembers.NICKNAME, Imps.GroupMembers.ROLE, Imps.GroupMembers.AFFILIATION};
        Uri memberUri = ContentUris.withAppendedId(Imps.GroupMembers.CONTENT_URI, getId());

        Cursor c = mContentResolver.query(memberUri, projection, null, null, null);
        if (c != null) {

            int memberCount = c.getCount();
            c.close();

            return memberCount;
        }

        return -1;
    }

}
