package org.telegram.messenger.matrix;

import android.text.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class MatrixController {

    private static volatile MatrixController[] instances = new MatrixController[4];

    private final int currentAccount;
    private final MatrixIdMapper idMapper;
    private boolean isRunning = false;
    private boolean isSyncing = false;

    public static MatrixController getInstance(int account) {
        if (account < 0 || account >= instances.length) {
            account = 0;
        }
        MatrixController local = instances[account];
        if (local == null) {
            synchronized (MatrixController.class) {
                local = instances[account];
                if (local == null) {
                    instances[account] = local = new MatrixController(account);
                }
            }
        }
        return local;
    }

    private MatrixController(int account) {
        this.currentAccount = account;
        this.idMapper = MatrixIdMapper.getInstance(account);
    }

    public boolean isMatrixActive() {
        return MatrixConfig.isMatrixActive(currentAccount);
    }

    public synchronized void start() {
        if (!isMatrixActive() || isRunning) {
            return;
        }
        isRunning = true;
        FileLog.d("MatrixController[" + currentAccount + "] started");
        scheduleSync(0);
    }

    public synchronized void stop() {
        isRunning = false;
        FileLog.d("MatrixController[" + currentAccount + "] stopped");
    }

    private void scheduleSync(int delayMs) {
        if (!isRunning) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            if (!isRunning || isSyncing) {
                return;
            }
            performSync();
        }, delayMs);
    }

    private void performSync() {
        if (!isRunning) {
            return;
        }
        isSyncing = true;
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);
        String since = MatrixConfig.getSyncBatch(currentAccount);

        MatrixClient.getInstance().sync(homeserver, accessToken, since, 30, new MatrixClient.MatrixCallback<MatrixClient.SyncResponse>() {
            @Override
            public void onSuccess(MatrixClient.SyncResponse result) {
                isSyncing = false;
                if (!isRunning) {
                    return;
                }
                if (!TextUtils.isEmpty(result.nextBatch)) {
                    MatrixConfig.setSyncBatch(currentAccount, result.nextBatch);
                }
                processSyncResponse(result.fullJson);
                scheduleSync(500);
            }

            @Override
            public void onError(int code, String message) {
                isSyncing = false;
                FileLog.e("MatrixController sync error: " + code + " " + message);
                scheduleSync(5000);
            }
        });
    }

    private void processSyncResponse(JSONObject fullJson) {
        if (fullJson == null) {
            return;
        }
        try {
            String myMatrixId = MatrixConfig.getUserId(currentAccount);
            String homeserver = MatrixConfig.getHomeserver(currentAccount);
            String accessToken = MatrixConfig.getAccessToken(currentAccount);

            // 1. Process Account Data (e.g. m.direct mapping)
            JSONObject accountData = fullJson.optJSONObject("account_data");
            if (accountData != null) {
                JSONArray events = accountData.optJSONArray("events");
                if (events != null) {
                    for (int i = 0; i < events.length(); i++) {
                        JSONObject ev = events.getJSONObject(i);
                        if ("m.direct".equals(ev.optString("type"))) {
                            JSONObject content = ev.optJSONObject("content");
                            if (content != null) {
                                Iterator<String> userKeys = content.keys();
                                while (userKeys.hasNext()) {
                                    String targetUserId = userKeys.next();
                                    JSONArray roomArray = content.optJSONArray(targetUserId);
                                    if (roomArray != null && roomArray.length() > 0) {
                                        String rId = roomArray.optString(0);
                                        if (!TextUtils.isEmpty(rId)) {
                                            long pUid = idMapper.getNumericUserId(targetUserId);
                                            idMapper.registerDirectRoom(rId, pUid);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            JSONObject roomsObj = fullJson.optJSONObject("rooms");
            if (roomsObj == null) {
                return;
            }

            // 2. Process Invited Rooms (Auto-join incoming invites & DMs)
            JSONObject inviteObj = roomsObj.optJSONObject("invite");
            if (inviteObj != null) {
                Iterator<String> inviteKeys = inviteObj.keys();
                while (inviteKeys.hasNext()) {
                    String invitedRoomId = inviteKeys.next();
                    JSONObject roomData = inviteObj.getJSONObject(invitedRoomId);
                    JSONObject inviteState = roomData.optJSONObject("invite_state");
                    String inviter = null;
                    boolean isDirect = false;

                    if (inviteState != null) {
                        JSONArray stateEvents = inviteState.optJSONArray("events");
                        if (stateEvents != null) {
                            for (int i = 0; i < stateEvents.length(); i++) {
                                JSONObject ev = stateEvents.getJSONObject(i);
                                if ("m.room.member".equals(ev.optString("type")) && myMatrixId.equals(ev.optString("state_key"))) {
                                    inviter = ev.optString("sender");
                                    JSONObject content = ev.optJSONObject("content");
                                    if (content != null && content.optBoolean("is_direct", false)) {
                                        isDirect = true;
                                    }
                                    break;
                                }
                            }
                        }
                    }

                    if (!TextUtils.isEmpty(inviter)) {
                        long inviterNumericId = idMapper.getNumericUserId(inviter);
                        if (isDirect) {
                            idMapper.registerDirectRoom(invitedRoomId, inviterNumericId);
                        }
                    }

                    // Auto-join invited room so it transitions to joined on next sync
                    MatrixClient.getInstance().joinRoom(homeserver, accessToken, invitedRoomId, null);
                }
            }

            // 3. Process Joined Rooms
            JSONObject joinObj = roomsObj.optJSONObject("join");
            if (joinObj == null) {
                return;
            }

            ArrayList<TLRPC.Chat> newChats = new ArrayList<>();
            ArrayList<TLRPC.User> newUsers = new ArrayList<>();
            ArrayList<TLRPC.Message> newMessages = new ArrayList<>();
            ArrayList<TLRPC.Dialog> newDialogs = new ArrayList<>();

            Iterator<String> roomKeys = joinObj.keys();
            while (roomKeys.hasNext()) {
                String roomId = roomKeys.next();
                JSONObject roomData = joinObj.getJSONObject(roomId);

                String roomName = null;
                String roomTopic = null;
                int memberCount = 0;
                List<String> memberUserIds = new ArrayList<>();
                String otherMemberId = null;
                String otherDisplayName = null;
                String otherAvatarUrl = null;

                // Process Room State
                JSONObject stateObj = roomData.optJSONObject("state");
                if (stateObj != null) {
                    JSONArray stateEvents = stateObj.optJSONArray("events");
                    if (stateEvents != null) {
                        for (int i = 0; i < stateEvents.length(); i++) {
                            JSONObject ev = stateEvents.getJSONObject(i);
                            String type = ev.optString("type");
                            JSONObject content = ev.optJSONObject("content");
                            if (content == null) continue;

                            if ("m.room.name".equals(type)) {
                                roomName = content.optString("name", roomName);
                            } else if ("m.room.topic".equals(type)) {
                                roomTopic = content.optString("topic", roomTopic);
                            } else if ("m.room.member".equals(type)) {
                                String memberUserId = ev.optString("state_key");
                                String membership = content.optString("membership", "join");
                                if ("join".equals(membership) || "invite".equals(membership)) {
                                    memberCount++;
                                    if (!TextUtils.isEmpty(memberUserId)) {
                                        memberUserIds.add(memberUserId);
                                        String displayName = content.optString("displayname", memberUserId);
                                        String avatarUrl = content.optString("avatar_url", null);
                                        long uId = idMapper.getNumericUserId(memberUserId);
                                        idMapper.setUserNameAndAvatar(uId, displayName, avatarUrl);
                                        TLRPC.TL_user user = createTLUser(uId, memberUserId, displayName, avatarUrl, memberUserId.equals(myMatrixId));
                                        newUsers.add(user);

                                        if (!memberUserId.equals(myMatrixId)) {
                                            otherMemberId = memberUserId;
                                            otherDisplayName = displayName;
                                            otherAvatarUrl = avatarUrl;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Determine if this is a Direct Chat (1-on-1) or a Group Chat
                boolean isDirect = idMapper.isDirectRoom(roomId);
                if (!isDirect && memberCount == 2 && otherMemberId != null && TextUtils.isEmpty(roomName)) {
                    isDirect = true;
                    long pUid = idMapper.getNumericUserId(otherMemberId);
                    idMapper.registerDirectRoom(roomId, pUid);
                }

                long dialogId;
                TLRPC.Peer dialogPeer;

                if (isDirect) {
                    long peerUserId = idMapper.getDirectRoomPeerUser(roomId);
                    if (peerUserId <= 0 && otherMemberId != null) {
                        peerUserId = idMapper.getNumericUserId(otherMemberId);
                        idMapper.registerDirectRoom(roomId, peerUserId);
                    }
                    if (peerUserId <= 0) {
                        continue;
                    }
                    dialogId = peerUserId; // Positive ID for 1-on-1 direct user dialogs
                    TLRPC.TL_peerUser peerUser = new TLRPC.TL_peerUser();
                    peerUser.user_id = peerUserId;
                    dialogPeer = peerUser;

                    // Ensure user is in list
                    if (otherMemberId != null) {
                        TLRPC.TL_user peerUserObj = createTLUser(peerUserId, otherMemberId, otherDisplayName, otherAvatarUrl, false);
                        newUsers.add(peerUserObj);
                    }
                } else {
                    long chatId = idMapper.getNumericChatId(roomId);
                    dialogId = -chatId; // Negative ID for group dialogs
                    TLRPC.TL_peerChat peerChat = new TLRPC.TL_peerChat();
                    peerChat.chat_id = chatId;
                    dialogPeer = peerChat;

                    if (TextUtils.isEmpty(roomName)) {
                        roomName = "Matrix Group " + chatId;
                    }
                    TLRPC.TL_chat chat = createTLChat(chatId, roomName, roomTopic, Math.max(memberCount, 2));
                    newChats.add(chat);
                }

                // Process Ephemeral Events (Typing, Receipts)
                JSONObject ephemeralObj = roomData.optJSONObject("ephemeral");
                if (ephemeralObj != null) {
                    JSONArray epEvents = ephemeralObj.optJSONArray("events");
                    if (epEvents != null) {
                        for (int i = 0; i < epEvents.length(); i++) {
                            JSONObject ev = epEvents.getJSONObject(i);
                            String type = ev.optString("type");
                            if ("m.typing".equals(type)) {
                                JSONObject content = ev.optJSONObject("content");
                                if (content != null) {
                                    JSONArray userIds = content.optJSONArray("user_ids");
                                    if (userIds != null && userIds.length() > 0) {
                                        for (int u = 0; u < userIds.length(); u++) {
                                            String typingUid = userIds.optString(u);
                                            if (!typingUid.equals(myMatrixId)) {
                                                long numUid = idMapper.getNumericUserId(typingUid);
                                                AndroidUtilities.runOnUIThread(() -> {
                                                    ArrayList<TLRPC.Update> updates = new ArrayList<>();
                                                    if (dialogId > 0) {
                                                        TLRPC.TL_updateUserTyping typingUpdate = new TLRPC.TL_updateUserTyping();
                                                        typingUpdate.user_id = numUid;
                                                        typingUpdate.action = new TLRPC.TL_sendMessageTypingAction();
                                                        updates.add(typingUpdate);
                                                    } else {
                                                        TLRPC.TL_updateChatUserTyping typingUpdate = new TLRPC.TL_updateChatUserTyping();
                                                        typingUpdate.chat_id = Math.abs(dialogId);
                                                        TLRPC.TL_peerUser peerUser = new TLRPC.TL_peerUser();
                                                        peerUser.user_id = numUid;
                                                        typingUpdate.from_id = peerUser;
                                                        typingUpdate.action = new TLRPC.TL_sendMessageTypingAction();
                                                        updates.add(typingUpdate);
                                                    }
                                                    MessagesController.getInstance(currentAccount).processUpdateArray(updates, null, null, false, 0);
                                                });
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Process Timeline Events
                JSONObject timelineObj = roomData.optJSONObject("timeline");
                int topMessageId = 0;
                int topMessageDate = 0;

                if (timelineObj != null) {
                    JSONArray timelineEvents = timelineObj.optJSONArray("events");
                    if (timelineEvents != null) {
                        for (int i = 0; i < timelineEvents.length(); i++) {
                            JSONObject ev = timelineEvents.getJSONObject(i);
                            String type = ev.optString("type");

                            // Handle redactions (deleted messages)
                            if ("m.room.redaction".equals(type)) {
                                String redactsEventId = ev.optString("redacts");
                                if (!TextUtils.isEmpty(redactsEventId)) {
                                    int redMsgId = idMapper.getNumericMessageId(roomId, redactsEventId);
                                    if (redMsgId > 0) {
                                        ArrayList<Integer> arr = new ArrayList<>();
                                        arr.add(redMsgId);
                                        MessagesStorage.getInstance(currentAccount).markMessagesAsDeleted(dialogId, arr, true, true, 0, 0);
                                    }
                                }
                                continue;
                            }

                            if (!"m.room.message".equals(type)) {
                                continue;
                            }

                            JSONObject content = ev.optJSONObject("content");
                            if (content == null) continue;

                            String eventId = ev.optString("event_id");
                            String senderId = ev.optString("sender");
                            long originServerTs = ev.optLong("origin_server_ts", System.currentTimeMillis());
                            String body = content.optString("body", "");
                            String msgType = content.optString("msgtype", "m.text");

                            int msgId = idMapper.getNumericMessageId(roomId, eventId);
                            long senderNumericId = idMapper.getNumericUserId(senderId);
                            boolean isOut = senderId.equals(myMatrixId);

                            TLRPC.TL_message msg = new TLRPC.TL_message();
                            msg.id = msgId;
                            msg.date = (int) (originServerTs / 1000);
                            msg.message = body;
                            msg.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID;

                            TLRPC.TL_peerUser fromPeer = new TLRPC.TL_peerUser();
                            fromPeer.user_id = senderNumericId;
                            msg.from_id = fromPeer;
                            msg.peer_id = dialogPeer;
                            msg.dialog_id = dialogId;

                            if (isOut) {
                                msg.out = true;
                                msg.flags |= 2;
                            }
                            msg.send_state = 0; // Sent

                            // Check for reply (m.in_reply_to)
                            JSONObject relatesTo = content.optJSONObject("m.relates_to");
                            if (relatesTo != null) {
                                JSONObject inReplyTo = relatesTo.optJSONObject("m.in_reply_to");
                                if (inReplyTo != null) {
                                    String replyToEventId = inReplyTo.optString("event_id");
                                    if (!TextUtils.isEmpty(replyToEventId)) {
                                        int replyMsgId = idMapper.getNumericMessageId(roomId, replyToEventId);
                                        if (replyMsgId > 0) {
                                            TLRPC.TL_messageReplyHeader replyHeader = new TLRPC.TL_messageReplyHeader();
                                            replyHeader.reply_to_msg_id = replyMsgId;
                                            msg.reply_to = replyHeader;
                                            msg.flags |= 8;
                                        }
                                    }
                                }
                            }

                            newMessages.add(msg);
                            topMessageId = Math.max(topMessageId, msgId);
                            topMessageDate = Math.max(topMessageDate, msg.date);
                        }
                    }
                }

                // Create and add Dialog
                TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
                dialog.id = dialogId;
                dialog.peer = dialogPeer;
                dialog.notify_settings = new TLRPC.TL_peerNotifySettings();
                dialog.unread_count = 0;
                if (topMessageId > 0) {
                    dialog.top_message = topMessageId;
                    dialog.last_message_date = topMessageDate;
                } else {
                    dialog.last_message_date = (int) (System.currentTimeMillis() / 1000);
                }
                newDialogs.add(dialog);
            }

            // Save and notify Telegram
            if (!newUsers.isEmpty() || !newChats.isEmpty()) {
                MessagesStorage.getInstance(currentAccount).putUsersAndChats(newUsers, newChats, true, true);
                MessagesController.getInstance(currentAccount).putUsers(newUsers, false);
                MessagesController.getInstance(currentAccount).putChats(newChats, false);
            }

            if (!newMessages.isEmpty()) {
                MessagesStorage.getInstance(currentAccount).putMessages(newMessages, true, true, false, 0, false, 0, 0);
            }

            if (!newDialogs.isEmpty()) {
                TLRPC.TL_messages_dialogs dialogsRes = new TLRPC.TL_messages_dialogs();
                dialogsRes.dialogs.addAll(newDialogs);
                dialogsRes.chats.addAll(newChats);
                dialogsRes.users.addAll(newUsers);
                dialogsRes.messages.addAll(newMessages);
                MessagesController.getInstance(currentAccount).processLoadedDialogs(dialogsRes, null, null, 0, 0, newDialogs.size(), 0, false, false, false);
            }

            AndroidUtilities.runOnUIThread(() -> {
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.dialogsNeedReload);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
                if (!newMessages.isEmpty()) {
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.messagesDidLoad, newDialogs.isEmpty() ? 0 : newDialogs.get(0).id);
                }
            });

        } catch (Exception e) {
            FileLog.e("MatrixController processSyncResponse error", e);
        }
    }

    // ---------------- Request Interception ----------------

    public boolean handleRequest(TLObject object, RequestDelegate onComplete) {
        if (!isMatrixActive()) {
            return false;
        }

        if (object instanceof TLRPC.TL_messages_sendMessage) {
            handleSendMessage((TLRPC.TL_messages_sendMessage) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_messages_getHistory) {
            handleGetHistory((TLRPC.TL_messages_getHistory) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_messages_readHistory) {
            handleReadHistory((TLRPC.TL_messages_readHistory) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_messages_setTyping) {
            handleSetTyping((TLRPC.TL_messages_setTyping) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_messages_deleteMessages) {
            handleDeleteMessages((TLRPC.TL_messages_deleteMessages) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_messages_editMessage) {
            handleEditMessage((TLRPC.TL_messages_editMessage) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_messages_createChat) {
            handleCreateChat((TLRPC.TL_messages_createChat) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_contacts_search) {
            handleContactsSearch((TLRPC.TL_contacts_search) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_contacts_getContacts) {
            handleGetContacts((TLRPC.TL_contacts_getContacts) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_users_getFullUser) {
            handleGetFullUser((TLRPC.TL_users_getFullUser) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_messages_getFullChat) {
            handleGetFullChat((TLRPC.TL_messages_getFullChat) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_messages_getDialogs) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_messages_dialogs(), null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_account_updateStatus) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_boolTrue(), null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_help_getConfig || object instanceof TLRPC.TL_help_getAppConfig) {
            if (onComplete != null) {
                TLRPC.TL_config config = new TLRPC.TL_config();
                config.expires = (int) (System.currentTimeMillis() / 1000) + 86400;
                config.date = (int) (System.currentTimeMillis() / 1000);
                onComplete.run(config, null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_account_getWallPapers) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_account_wallPapers(), null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_messages_getAttachMenuBots) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_attachMenuBots(), null);
            }
            return true;
        }

        return false;
    }

    private void handleSendMessage(TLRPC.TL_messages_sendMessage req, RequestDelegate onComplete) {
        long peerDialogId = DialogObject.getPeerDialogId(req.peer);
        String matrixRoomId = idMapper.getMatrixRoomIdForDialog(peerDialogId);
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        String replyToEventId = null;
        if (req.reply_to != null && req.reply_to.reply_to_msg_id > 0 && !TextUtils.isEmpty(matrixRoomId)) {
            replyToEventId = idMapper.getMatrixEventId(matrixRoomId, req.reply_to.reply_to_msg_id);
        }
        final String finalReplyEventId = replyToEventId;

        // If this is a direct message to a user who doesn't have an existing Matrix room yet, auto-create the room!
        if (TextUtils.isEmpty(matrixRoomId) && peerDialogId > 0) {
            String peerMatrixId = idMapper.getMatrixUserId(peerDialogId);
            if (TextUtils.isEmpty(peerMatrixId)) {
                TLRPC.User u = MessagesController.getInstance(currentAccount).getUser(peerDialogId);
                if (u != null && !TextUtils.isEmpty(u.phone) && u.phone.startsWith("@")) {
                    peerMatrixId = u.phone;
                }
            }

            if (!TextUtils.isEmpty(peerMatrixId)) {
                final String targetMatrixId = peerMatrixId;
                MatrixClient.getInstance().createDirectRoom(homeserver, accessToken, targetMatrixId, new MatrixClient.MatrixCallback<String>() {
                    @Override
                    public void onSuccess(String newRoomId) {
                        idMapper.registerDirectRoom(newRoomId, peerDialogId);
                        doSendMessage(homeserver, accessToken, newRoomId, req.message, finalReplyEventId, peerDialogId, onComplete);
                    }

                    @Override
                    public void onError(int code, String message) {
                        FileLog.e("Failed to create direct room: " + code + " " + message);
                        if (onComplete != null) {
                            TLRPC.TL_error err = new TLRPC.TL_error();
                            err.code = code;
                            err.text = message;
                            onComplete.run(null, err);
                        }
                    }
                });
                return;
            }
        }

        if (TextUtils.isEmpty(matrixRoomId)) {
            if (onComplete != null) {
                TLRPC.TL_error err = new TLRPC.TL_error();
                err.code = 400;
                err.text = "CHAT_NOT_FOUND";
                onComplete.run(null, err);
            }
            return;
        }

        doSendMessage(homeserver, accessToken, matrixRoomId, req.message, finalReplyEventId, peerDialogId, onComplete);
    }

    private void doSendMessage(String homeserver, String accessToken, String roomId, String text, String replyToEventId, long peerDialogId, RequestDelegate onComplete) {
        String txnId = idMapper.getNextTxnId();

        MatrixClient.getInstance().sendMessageWithReply(homeserver, accessToken, roomId, text, replyToEventId, txnId, new MatrixClient.MatrixCallback<String>() {
            @Override
            public void onSuccess(String eventId) {
                int numericMsgId = idMapper.getNumericMessageId(roomId, eventId);

                TLRPC.TL_updateShortSentMessage sentMessage = new TLRPC.TL_updateShortSentMessage();
                sentMessage.id = numericMsgId;
                sentMessage.date = (int) (System.currentTimeMillis() / 1000);
                sentMessage.out = true;

                if (onComplete != null) {
                    onComplete.run(sentMessage, null);
                }

                AndroidUtilities.runOnUIThread(() -> {
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.dialogsNeedReload);
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
                });
            }

            @Override
            public void onError(int code, String message) {
                if (onComplete != null) {
                    TLRPC.TL_error err = new TLRPC.TL_error();
                    err.code = code;
                    err.text = message;
                    onComplete.run(null, err);
                }
            }
        });
    }

    private void handleGetHistory(TLRPC.TL_messages_getHistory req, RequestDelegate onComplete) {
        long peerDialogId = DialogObject.getPeerDialogId(req.peer);
        String matrixRoomId = idMapper.getMatrixRoomIdForDialog(peerDialogId);

        if (TextUtils.isEmpty(matrixRoomId)) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_messages_messages(), null);
            }
            return;
        }

        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        MatrixClient.getInstance().loadRoomMessages(homeserver, accessToken, matrixRoomId, null, req.limit > 0 ? req.limit : 30, new MatrixClient.MatrixCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject json) {
                TLRPC.TL_messages_messages messagesRes = new TLRPC.TL_messages_messages();
                JSONArray chunk = json.optJSONArray("chunk");
                String myMatrixId = MatrixConfig.getUserId(currentAccount);

                TLRPC.Peer dialogPeer;
                if (peerDialogId > 0) {
                    TLRPC.TL_peerUser pu = new TLRPC.TL_peerUser();
                    pu.user_id = peerDialogId;
                    dialogPeer = pu;
                } else {
                    TLRPC.TL_peerChat pc = new TLRPC.TL_peerChat();
                    pc.chat_id = Math.abs(peerDialogId);
                    dialogPeer = pc;
                }

                if (chunk != null) {
                    for (int i = 0; i < chunk.length(); i++) {
                        JSONObject ev = chunk.optJSONObject(i);
                        if (ev == null || !"m.room.message".equals(ev.optString("type"))) {
                            continue;
                        }
                        JSONObject content = ev.optJSONObject("content");
                        if (content == null) continue;

                        String eventId = ev.optString("event_id");
                        String senderId = ev.optString("sender");
                        long originServerTs = ev.optLong("origin_server_ts", System.currentTimeMillis());
                        String body = content.optString("body", "");

                        int msgId = idMapper.getNumericMessageId(matrixRoomId, eventId);
                        long senderNumericId = idMapper.getNumericUserId(senderId);

                        TLRPC.TL_message msg = new TLRPC.TL_message();
                        msg.id = msgId;
                        msg.date = (int) (originServerTs / 1000);
                        msg.message = body;
                        msg.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID;

                        TLRPC.TL_peerUser fromPeer = new TLRPC.TL_peerUser();
                        fromPeer.user_id = senderNumericId;
                        msg.from_id = fromPeer;
                        msg.peer_id = dialogPeer;
                        msg.dialog_id = peerDialogId;

                        if (senderId.equals(myMatrixId)) {
                            msg.out = true;
                            msg.flags |= 2;
                        }
                        msg.send_state = 0;

                        // Ensure sender User object is included
                        TLRPC.TL_user senderUser = createTLUser(senderNumericId, senderId, idMapper.getUserDisplayName(senderNumericId), idMapper.getUserAvatarUrl(senderNumericId), senderId.equals(myMatrixId));
                        messagesRes.users.add(senderUser);
                        messagesRes.messages.add(msg);
                    }
                }

                if (onComplete != null) {
                    onComplete.run(messagesRes, null);
                }
            }

            @Override
            public void onError(int code, String message) {
                if (onComplete != null) {
                    onComplete.run(new TLRPC.TL_messages_messages(), null);
                }
            }
        });
    }

    private void handleReadHistory(TLRPC.TL_messages_readHistory req, RequestDelegate onComplete) {
        long peerDialogId = DialogObject.getPeerDialogId(req.peer);
        String matrixRoomId = idMapper.getMatrixRoomIdForDialog(peerDialogId);

        if (!TextUtils.isEmpty(matrixRoomId) && req.max_id > 0) {
            String eventId = idMapper.getMatrixEventId(matrixRoomId, req.max_id);
            if (!TextUtils.isEmpty(eventId)) {
                String homeserver = MatrixConfig.getHomeserver(currentAccount);
                String accessToken = MatrixConfig.getAccessToken(currentAccount);
                MatrixClient.getInstance().sendReadReceipt(homeserver, accessToken, matrixRoomId, eventId, null);
            }
        }

        if (onComplete != null) {
            TLRPC.TL_messages_affectedMessages affected = new TLRPC.TL_messages_affectedMessages();
            affected.pts = 1;
            affected.pts_count = 1;
            onComplete.run(affected, null);
        }
    }

    private void handleSetTyping(TLRPC.TL_messages_setTyping req, RequestDelegate onComplete) {
        long peerDialogId = DialogObject.getPeerDialogId(req.peer);
        String roomId = idMapper.getMatrixRoomIdForDialog(peerDialogId);
        String myMatrixId = MatrixConfig.getUserId(currentAccount);

        if (!TextUtils.isEmpty(roomId) && !TextUtils.isEmpty(myMatrixId)) {
            String homeserver = MatrixConfig.getHomeserver(currentAccount);
            String accessToken = MatrixConfig.getAccessToken(currentAccount);
            boolean isTyping = req.action instanceof TLRPC.TL_sendMessageTypingAction;
            MatrixClient.getInstance().setTyping(homeserver, accessToken, roomId, myMatrixId, isTyping, 10000, null);
        }

        if (onComplete != null) {
            onComplete.run(new TLRPC.TL_boolTrue(), null);
        }
    }

    private void handleDeleteMessages(TLRPC.TL_messages_deleteMessages req, RequestDelegate onComplete) {
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        if (req.id != null) {
            for (Integer msgId : req.id) {
                for (String key : idMapper.getPrefs().getAll().keySet()) {
                    if (key.endsWith(":" + msgId) && key.startsWith("re_")) {
                        String eventId = idMapper.getPrefs().getString(key, null);
                        String roomId = key.substring(3, key.indexOf(":" + msgId));
                        if (!TextUtils.isEmpty(roomId) && !TextUtils.isEmpty(eventId)) {
                            MatrixClient.getInstance().redactEvent(homeserver, accessToken, roomId, eventId, idMapper.getNextTxnId(), "Deleted by user", null);
                        }
                        break;
                    }
                }
            }
        }

        if (onComplete != null) {
            TLRPC.TL_messages_affectedMessages affected = new TLRPC.TL_messages_affectedMessages();
            affected.pts = 1;
            affected.pts_count = req.id != null ? req.id.size() : 1;
            onComplete.run(affected, null);
        }
    }

    private void handleEditMessage(TLRPC.TL_messages_editMessage req, RequestDelegate onComplete) {
        long peerDialogId = DialogObject.getPeerDialogId(req.peer);
        String roomId = idMapper.getMatrixRoomIdForDialog(peerDialogId);
        String eventId = idMapper.getMatrixEventId(roomId, req.id);
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        if (!TextUtils.isEmpty(roomId) && !TextUtils.isEmpty(eventId)) {
            MatrixClient.getInstance().editMessage(homeserver, accessToken, roomId, eventId, req.message, idMapper.getNextTxnId(), new MatrixClient.MatrixCallback<String>() {
                @Override
                public void onSuccess(String editEventId) {
                    TLRPC.TL_updates updates = new TLRPC.TL_updates();
                    updates.date = (int) (System.currentTimeMillis() / 1000);
                    if (onComplete != null) {
                        onComplete.run(updates, null);
                    }
                }

                @Override
                public void onError(int code, String message) {
                    if (onComplete != null) {
                        TLRPC.TL_error err = new TLRPC.TL_error();
                        err.code = code;
                        err.text = message;
                        onComplete.run(null, err);
                    }
                }
            });
        } else {
            if (onComplete != null) {
                TLRPC.TL_updates updates = new TLRPC.TL_updates();
                onComplete.run(updates, null);
            }
        }
    }

    private void handleCreateChat(TLRPC.TL_messages_createChat req, RequestDelegate onComplete) {
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);
        List<String> inviteMatrixIds = new ArrayList<>();

        if (req.users != null) {
            for (TLRPC.InputUser iu : req.users) {
                if (iu instanceof TLRPC.TL_inputUser) {
                    String mid = idMapper.getMatrixUserId(iu.user_id);
                    if (!TextUtils.isEmpty(mid)) {
                        inviteMatrixIds.add(mid);
                    }
                }
            }
        }

        MatrixClient.getInstance().createGroupRoom(homeserver, accessToken, req.title, inviteMatrixIds, new MatrixClient.MatrixCallback<String>() {
            @Override
            public void onSuccess(String roomId) {
                long chatId = idMapper.getNumericChatId(roomId);
                TLRPC.TL_chat newChat = createTLChat(chatId, req.title, "", Math.max(2, inviteMatrixIds.size() + 1));
                MessagesController.getInstance(currentAccount).putChat(newChat, false);

                TLRPC.TL_updates updates = new TLRPC.TL_updates();
                updates.chats.add(newChat);
                updates.date = (int) (System.currentTimeMillis() / 1000);

                if (onComplete != null) {
                    onComplete.run(updates, null);
                }

                AndroidUtilities.runOnUIThread(() -> {
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.dialogsNeedReload);
                });
            }

            @Override
            public void onError(int code, String message) {
                if (onComplete != null) {
                    TLRPC.TL_error err = new TLRPC.TL_error();
                    err.code = code;
                    err.text = message;
                    onComplete.run(null, err);
                }
            }
        });
    }

    private void handleContactsSearch(TLRPC.TL_contacts_search req, RequestDelegate onComplete) {
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);
        String query = req.q != null ? req.q.trim() : "";

        if (TextUtils.isEmpty(query)) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_contacts_found(), null);
            }
            return;
        }

        // Direct Matrix ID lookup (e.g. @user:matrix.org)
        if (query.startsWith("@") && query.contains(":")) {
            MatrixClient.getInstance().getUserProfile(homeserver, accessToken, query, new MatrixClient.MatrixCallback<MatrixClient.MatrixUser>() {
                @Override
                public void onSuccess(MatrixClient.MatrixUser mu) {
                    TLRPC.TL_contacts_found found = new TLRPC.TL_contacts_found();
                    long uid = idMapper.getNumericUserId(mu.userId);
                    idMapper.setUserNameAndAvatar(uid, mu.displayName, mu.avatarUrl);
                    TLRPC.TL_user tu = createTLUser(uid, mu.userId, mu.displayName, mu.avatarUrl, false);
                    MessagesController.getInstance(currentAccount).putUser(tu, false);

                    TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
                    peer.user_id = uid;
                    found.results.add(peer);
                    found.users.add(tu);
                    if (onComplete != null) {
                        onComplete.run(found, null);
                    }
                }

                @Override
                public void onError(int code, String message) {
                    searchDirectory(homeserver, accessToken, query, req.limit, onComplete);
                }
            });
            return;
        }

        searchDirectory(homeserver, accessToken, query, req.limit, onComplete);
    }

    private void searchDirectory(String homeserver, String accessToken, String query, int limit, RequestDelegate onComplete) {
        MatrixClient.getInstance().searchUsers(homeserver, accessToken, query, limit > 0 ? limit : 20, new MatrixClient.MatrixCallback<List<MatrixClient.MatrixUser>>() {
            @Override
            public void onSuccess(List<MatrixClient.MatrixUser> results) {
                TLRPC.TL_contacts_found found = new TLRPC.TL_contacts_found();
                for (MatrixClient.MatrixUser mu : results) {
                    long uid = idMapper.getNumericUserId(mu.userId);
                    idMapper.setUserNameAndAvatar(uid, mu.displayName, mu.avatarUrl);
                    TLRPC.TL_user tu = createTLUser(uid, mu.userId, mu.displayName, mu.avatarUrl, false);
                    MessagesController.getInstance(currentAccount).putUser(tu, false);

                    TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
                    peer.user_id = uid;
                    found.results.add(peer);
                    found.users.add(tu);
                }
                if (onComplete != null) {
                    onComplete.run(found, null);
                }
            }

            @Override
            public void onError(int code, String message) {
                if (onComplete != null) {
                    onComplete.run(new TLRPC.TL_contacts_found(), null);
                }
            }
        });
    }

    private void handleGetContacts(TLRPC.TL_contacts_getContacts req, RequestDelegate onComplete) {
        TLRPC.TL_contacts_contacts res = new TLRPC.TL_contacts_contacts();
        List<Long> directUserIds = idMapper.getAllDirectUserIds();
        for (Long uid : directUserIds) {
            String matrixId = idMapper.getMatrixUserId(uid);
            String displayName = idMapper.getUserDisplayName(uid);
            String avatarUrl = idMapper.getUserAvatarUrl(uid);
            TLRPC.TL_user user = createTLUser(uid, matrixId, displayName, avatarUrl, false);
            res.users.add(user);

            TLRPC.TL_contact contact = new TLRPC.TL_contact();
            contact.user_id = uid;
            contact.mutual = true;
            res.contacts.add(contact);
        }
        res.saved_count = res.contacts.size();
        if (onComplete != null) {
            onComplete.run(res, null);
        }
    }

    private void handleGetFullUser(TLRPC.TL_users_getFullUser req, RequestDelegate onComplete) {
        long userId = 0;
        if (req.id instanceof TLRPC.TL_inputUser) {
            userId = req.id.user_id;
        } else if (req.id instanceof TLRPC.TL_inputUserSelf) {
            userId = UserConfig.getInstance(currentAccount).getClientUserId();
        }

        TLRPC.TL_users_userFull userFull = new TLRPC.TL_users_userFull();
        TLRPC.TL_userFull full = new TLRPC.TL_userFull();
        full.id = userId;
        String mId = idMapper.getMatrixUserId(userId);
        full.about = !TextUtils.isEmpty(mId) ? mId : "Matrix User";
        full.settings = new TLRPC.TL_peerSettings();
        full.notify_settings = new TLRPC.TL_peerNotifySettings();
        full.flags = 0;

        userFull.full_user = full;
        TLRPC.User u = MessagesController.getInstance(currentAccount).getUser(userId);
        if (u != null) {
            userFull.users.add(u);
        } else if (!TextUtils.isEmpty(mId)) {
            TLRPC.TL_user created = createTLUser(userId, mId, idMapper.getUserDisplayName(userId), idMapper.getUserAvatarUrl(userId), userId == UserConfig.getInstance(currentAccount).getClientUserId());
            userFull.users.add(created);
        }

        if (onComplete != null) {
            onComplete.run(userFull, null);
        }
    }

    private void handleGetFullChat(TLRPC.TL_messages_getFullChat req, RequestDelegate onComplete) {
        long chatId = req.chat_id;
        String roomId = idMapper.getMatrixRoomId(chatId);

        TLRPC.TL_messages_chatFull chatFull = new TLRPC.TL_messages_chatFull();
        TLRPC.TL_chatFull full = new TLRPC.TL_chatFull();
        full.id = chatId;
        full.about = !TextUtils.isEmpty(roomId) ? roomId : "Matrix Group";
        full.notify_settings = new TLRPC.TL_peerNotifySettings();
        full.participants = new TLRPC.TL_chatParticipants();
        full.participants.chat_id = chatId;

        chatFull.full_chat = full;
        TLRPC.Chat c = MessagesController.getInstance(currentAccount).getChat(chatId);
        if (c != null) {
            chatFull.chats.add(c);
        }

        if (onComplete != null) {
            onComplete.run(chatFull, null);
        }
    }

    private TLRPC.TL_user createTLUser(long numericId, String matrixUserId, String displayName, String avatarUrl, boolean isSelf) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = numericId;
        String name = !TextUtils.isEmpty(displayName) ? displayName : (matrixUserId != null ? matrixUserId : "User");
        int space = name.indexOf(' ');
        if (space > 0) {
            user.first_name = name.substring(0, space).trim();
            user.last_name = name.substring(space + 1).trim();
        } else {
            user.first_name = name;
            user.last_name = "";
        }
        if (matrixUserId != null) {
            user.username = matrixUserId.replaceFirst("^@", "").replace(':', '_');
            user.phone = matrixUserId;
        } else {
            user.username = "";
            user.phone = "";
        }
        user.self = isSelf;
        user.flags = 2 | 8 | 16 | 64 | (isSelf ? 1024 : 0);
        if (!TextUtils.isEmpty(user.last_name)) {
            user.flags |= 4;
        }
        user.status = new TLRPC.TL_userStatusOnline();
        user.status.expires = (int) (System.currentTimeMillis() / 1000) + 300;
        return user;
    }

    private TLRPC.TL_chat createTLChat(long chatId, String title, String topic, int memberCount) {
        TLRPC.TL_chat chat = new TLRPC.TL_chat();
        chat.id = chatId;
        chat.title = title;
        chat.participants_count = memberCount;
        chat.date = (int) (System.currentTimeMillis() / 1000);
        return chat;
    }
}
