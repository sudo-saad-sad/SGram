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
            JSONObject roomsObj = fullJson.optJSONObject("rooms");
            if (roomsObj == null) {
                return;
            }
            JSONObject joinObj = roomsObj.optJSONObject("join");
            if (joinObj == null) {
                return;
            }

            String myMatrixId = MatrixConfig.getUserId(currentAccount);
            long myNumericId = UserConfig.getInstance(currentAccount).getClientUserId();

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
                int memberCount = 2;

                // 1. Process State Events
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
                                String displayName = content.optString("displayname", memberUserId);
                                if (!TextUtils.isEmpty(memberUserId)) {
                                    long uId = idMapper.getNumericUserId(memberUserId);
                                    TLRPC.TL_user user = createTLUser(uId, memberUserId, displayName, memberUserId.equals(myMatrixId));
                                    newUsers.add(user);
                                }
                            }
                        }
                    }
                }

                // 2. Determine Room / Dialog IDs
                long chatId = idMapper.getNumericChatId(roomId);
                long dialogId = -chatId;

                if (TextUtils.isEmpty(roomName)) {
                    roomName = "Matrix Room " + chatId;
                }

                TLRPC.TL_chat chat = new TLRPC.TL_chat();
                chat.id = chatId;
                chat.title = roomName;
                chat.participants_count = memberCount;
                chat.date = (int) (System.currentTimeMillis() / 1000);
                newChats.add(chat);

                // 3. Process Timeline Events
                JSONObject timelineObj = roomData.optJSONObject("timeline");
                int topMessageId = 0;
                int topMessageDate = 0;

                if (timelineObj != null) {
                    JSONArray timelineEvents = timelineObj.optJSONArray("events");
                    if (timelineEvents != null) {
                        for (int i = 0; i < timelineEvents.length(); i++) {
                            JSONObject ev = timelineEvents.getJSONObject(i);
                            String type = ev.optString("type");
                            if (!"m.room.message".equals(type)) {
                                continue;
                            }

                            JSONObject content = ev.optJSONObject("content");
                            if (content == null) continue;

                            String eventId = ev.optString("event_id");
                            String senderId = ev.optString("sender");
                            long originServerTs = ev.optLong("origin_server_ts", System.currentTimeMillis());
                            String body = content.optString("body", "");

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

                            TLRPC.TL_peerChat peerChat = new TLRPC.TL_peerChat();
                            peerChat.chat_id = chatId;
                            msg.peer_id = peerChat;
                            msg.dialog_id = dialogId;

                            if (isOut) {
                                msg.out = true;
                                msg.flags |= TLRPC.MESSAGE_FLAG_OUT;
                            }
                            msg.send_state = 0; // sent

                            newMessages.add(msg);
                            topMessageId = Math.max(topMessageId, msgId);
                            topMessageDate = Math.max(topMessageDate, msg.date);
                        }
                    }
                }

                // 4. Create Dialog
                TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
                dialog.id = dialogId;
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
                for (TLRPC.Dialog d : newDialogs) {
                    MessagesStorage.getInstance(currentAccount).putDialog(d, false);
                }
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
        }

        return false;
    }

    private void handleSendMessage(TLRPC.TL_messages_sendMessage req, RequestDelegate onComplete) {
        long peerDialogId = DialogObject.getPeerDialogId(req.peer);
        String matrixRoomId = idMapper.getMatrixRoomIdForDialog(peerDialogId);

        if (TextUtils.isEmpty(matrixRoomId)) {
            if (onComplete != null) {
                TLRPC.TL_error err = new TLRPC.TL_error();
                err.code = 400;
                err.text = "CHAT_NOT_FOUND";
                onComplete.run(null, err);
            }
            return;
        }

        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);
        String txnId = idMapper.getNextTxnId();

        MatrixClient.getInstance().sendMessage(homeserver, accessToken, matrixRoomId, req.message, txnId, new MatrixClient.MatrixCallback<String>() {
            @Override
            public void onSuccess(String eventId) {
                int numericMsgId = idMapper.getNumericMessageId(matrixRoomId, eventId);

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

                        TLRPC.TL_peerChat peerChat = new TLRPC.TL_peerChat();
                        peerChat.chat_id = Math.abs(peerDialogId);
                        msg.peer_id = peerChat;
                        msg.dialog_id = peerDialogId;

                        if (senderId.equals(myMatrixId)) {
                            msg.out = true;
                            msg.flags |= TLRPC.MESSAGE_FLAG_OUT;
                        }
                        msg.send_state = 0;
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

    private TLRPC.TL_user createTLUser(long numericId, String matrixUserId, String displayName, boolean isSelf) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = numericId;
        user.first_name = !TextUtils.isEmpty(displayName) ? displayName : matrixUserId;
        user.username = matrixUserId.replaceFirst("^@", "").replace(':', '_');
        user.self = isSelf;
        user.status = new TLRPC.TL_userStatusOnline();
        user.status.expires = (int) (System.currentTimeMillis() / 1000) + 300;
        return user;
    }
}
