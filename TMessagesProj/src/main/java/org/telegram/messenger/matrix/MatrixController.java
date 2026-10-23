package org.telegram.messenger.matrix;

import android.text.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public class MatrixController {

    private static volatile MatrixController[] instances = new MatrixController[4];

    private final int currentAccount;
    private final MatrixIdMapper idMapper;
    private boolean isRunning = false;
    private boolean isSyncing = false;

    private final ConcurrentHashMap<String, String> roomPrevBatchMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, TreeMap<Integer, byte[]>> filePartBuffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> mediaIdToUrlMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> deviceHashToIdMap = new ConcurrentHashMap<>();
    private volatile TLRPC.messages_Dialogs cachedDialogs;

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

        // Immediately load cached dialogs and messages from SQLite storage on launch
        AndroidUtilities.runOnUIThread(() -> {
            MessagesController.getInstance(currentAccount).loadDialogs(0, 0, 100, true);
        });

        // Initialize and upload device encryption keys so other Matrix users can exchange keys and chat securely
        MatrixKeyManager.getInstance(currentAccount).initAndUploadKeys();

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

            // 0. Process To-Device Events (m.room_key, m.forwarded_room_key, key distribution)
            JSONObject toDeviceObj = fullJson.optJSONObject("to_device");
            if (toDeviceObj != null) {
                JSONArray toDeviceEvents = toDeviceObj.optJSONArray("events");
                if (toDeviceEvents != null && toDeviceEvents.length() > 0) {
                    MatrixKeyManager.getInstance(currentAccount).processToDeviceEvents(toDeviceEvents);
                }
            }

            // 1. Process Presence Events
            JSONObject presenceObj = fullJson.optJSONObject("presence");
            if (presenceObj != null) {
                JSONArray presenceEvents = presenceObj.optJSONArray("events");
                if (presenceEvents != null) {
                    for (int i = 0; i < presenceEvents.length(); i++) {
                        JSONObject ev = presenceEvents.getJSONObject(i);
                        String sender = ev.optString("sender");
                        JSONObject content = ev.optJSONObject("content");
                        if (content != null && !TextUtils.isEmpty(sender)) {
                            long numUid = idMapper.getNumericUserId(sender);
                            String presenceStr = content.optString("presence", "offline");
                            boolean isOnline = "online".equals(presenceStr);

                            TLRPC.User u = MessagesController.getInstance(currentAccount).getUser(numUid);
                            if (u != null) {
                                if (isOnline) {
                                    u.status = new TLRPC.TL_userStatusOnline();
                                    u.status.expires = (int) (System.currentTimeMillis() / 1000) + 300;
                                } else {
                                    u.status = new TLRPC.TL_userStatusOffline();
                                    long lastActive = content.optLong("last_active_ago", 0);
                                    u.status.expires = (int) ((System.currentTimeMillis() - lastActive) / 1000);
                                }
                            }
                        }
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_STATUS);
                    });
                }
            }

            // 2. Process Account Data (e.g. m.direct mapping)
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

            // 3. Process Invited Rooms (Auto-join incoming invites & DMs)
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

            // 4. Process Left Rooms
            JSONObject leaveObj = roomsObj.optJSONObject("leave");
            if (leaveObj != null) {
                Iterator<String> leaveKeys = leaveObj.keys();
                while (leaveKeys.hasNext()) {
                    String leftRoomId = leaveKeys.next();
                    long directPeer = idMapper.getDirectRoomPeerUser(leftRoomId);
                    long dialogId = directPeer > 0 ? directPeer : -idMapper.getNumericChatId(leftRoomId);
                    AndroidUtilities.runOnUIThread(() -> {
                        MessagesController.getInstance(currentAccount).deleteDialog(dialogId, 0);
                    });
                }
            }

            // 5. Process Joined Rooms
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
                String roomAvatarUrl = null;
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
                            } else if ("m.room.avatar".equals(type)) {
                                String aUrl = content.optString("url", null);
                                if (!TextUtils.isEmpty(aUrl)) {
                                    roomAvatarUrl = aUrl;
                                }
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
                    TLRPC.TL_chat chat = createTLChat(chatId, roomName, roomTopic, Math.max(memberCount, 2), roomAvatarUrl);
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
                            } else if ("m.receipt".equals(type)) {
                                JSONObject content = ev.optJSONObject("content");
                                if (content != null) {
                                    Iterator<String> evKeys = content.keys();
                                    while (evKeys.hasNext()) {
                                        String readEventId = evKeys.next();
                                        JSONObject receiptObj = content.optJSONObject(readEventId);
                                        if (receiptObj != null) {
                                            JSONObject mRead = receiptObj.optJSONObject("m.read");
                                            if (mRead != null) {
                                                Iterator<String> userKeys = mRead.keys();
                                                while (userKeys.hasNext()) {
                                                    String userWhoRead = userKeys.next();
                                                    if (!userWhoRead.equals(myMatrixId)) {
                                                        int readMsgId = idMapper.getNumericMessageId(roomId, readEventId);
                                                        if (readMsgId > 0) {
                                                            AndroidUtilities.runOnUIThread(() -> {
                                                                ArrayList<TLRPC.Update> arr = new ArrayList<>();
                                                                TLRPC.TL_updateReadHistoryOutbox outboxUpdate = new TLRPC.TL_updateReadHistoryOutbox();
                                                                outboxUpdate.peer = dialogPeer;
                                                                outboxUpdate.max_id = readMsgId;
                                                                outboxUpdate.pts = 1;
                                                                outboxUpdate.pts_count = 1;
                                                                arr.add(outboxUpdate);
                                                                MessagesController.getInstance(currentAccount).processUpdateArray(arr, null, null, false, 0);
                                                            });
                                                        }
                                                    }
                                                }
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
                    String prevBatch = timelineObj.optString("prev_batch", null);
                    if (!TextUtils.isEmpty(prevBatch)) {
                        roomPrevBatchMap.put(roomId, prevBatch);
                    }

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

                            // Handle emoji reactions
                            if ("m.reaction".equals(type)) {
                                JSONObject content = ev.optJSONObject("content");
                                if (content != null) {
                                    JSONObject relatesTo = content.optJSONObject("m.relates_to");
                                    if (relatesTo != null && "m.annotation".equals(relatesTo.optString("rel_type"))) {
                                        String targetEventId = relatesTo.optString("event_id");
                                        String reactionKey = relatesTo.optString("key");
                                        int targetMsgId = idMapper.getNumericMessageId(roomId, targetEventId);
                                        if (targetMsgId > 0 && !TextUtils.isEmpty(reactionKey)) {
                                            AndroidUtilities.runOnUIThread(() -> {
                                                ArrayList<TLRPC.Update> arr = new ArrayList<>();
                                                TLRPC.TL_updateMessageReactions update = new TLRPC.TL_updateMessageReactions();
                                                update.peer = dialogPeer;
                                                update.msg_id = targetMsgId;
                                                update.reactions = new TLRPC.TL_messageReactions();
                                                TLRPC.TL_reactionCount rc = new TLRPC.TL_reactionCount();
                                                TLRPC.TL_reactionEmoji re = new TLRPC.TL_reactionEmoji();
                                                re.emoticon = reactionKey;
                                                rc.reaction = re;
                                                rc.count = 1;
                                                update.reactions.results.add(rc);
                                                arr.add(update);
                                                MessagesController.getInstance(currentAccount).processUpdateArray(arr, null, null, false, 0);
                                            });
                                        }
                                    }
                                }
                                continue;
                            }

                            // Support m.room.message, m.room.encrypted, and polls
                            if (!"m.room.message".equals(type) && !"m.room.encrypted".equals(type) && !"org.matrix.msc3381.poll.start".equals(type) && !"m.poll.start".equals(type)) {
                                continue;
                            }

                            JSONObject content = ev.optJSONObject("content");
                            if (content == null) continue;

                            // Handle edits (m.replace)
                            JSONObject relatesTo = content.optJSONObject("m.relates_to");
                            if (relatesTo != null && "m.replace".equals(relatesTo.optString("rel_type"))) {
                                String targetEventId = relatesTo.optString("event_id");
                                JSONObject newContent = content.optJSONObject("m.new_content");
                                String editedText = newContent != null ? newContent.optString("body", content.optString("body", "")) : content.optString("body", "");
                                int targetMsgId = idMapper.getNumericMessageId(roomId, targetEventId);
                                if (targetMsgId > 0) {
                                    AndroidUtilities.runOnUIThread(() -> {
                                        TLRPC.TL_message editedMsg = new TLRPC.TL_message();
                                        editedMsg.id = targetMsgId;
                                        editedMsg.date = (int) (ev.optLong("origin_server_ts", System.currentTimeMillis()) / 1000);
                                        editedMsg.message = editedText;
                                        editedMsg.dialog_id = dialogId;
                                        editedMsg.peer_id = dialogPeer;
                                        editedMsg.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
                                        if (myMatrixId.equals(ev.optString("sender"))) {
                                            editedMsg.out = true;
                                            editedMsg.flags |= 2;
                                        }
                                        TLRPC.TL_updateEditMessage editUpdate = new TLRPC.TL_updateEditMessage();
                                        editUpdate.message = editedMsg;
                                        editUpdate.pts = 1;
                                        editUpdate.pts_count = 1;
                                        ArrayList<TLRPC.Update> arr = new ArrayList<>();
                                        arr.add(editUpdate);
                                        MessagesController.getInstance(currentAccount).processUpdateArray(arr, null, null, false, 0);
                                    });
                                }
                                continue;
                            }

                            TLRPC.TL_message msg = parseMatrixMessageEvent(roomId, ev, dialogId, dialogPeer, myMatrixId);
                            if (msg != null) {
                                newMessages.add(msg);
                                topMessageId = Math.max(topMessageId, msg.id);
                                topMessageDate = Math.max(topMessageDate, msg.date);
                            }
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
                cachedDialogs = dialogsRes;
                MessagesStorage.getInstance(currentAccount).putDialogs(dialogsRes, 0);
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
        } else if (object instanceof TLRPC.TL_messages_sendMedia) {
            handleSendMedia((TLRPC.TL_messages_sendMedia) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_upload_saveFilePart) {
            handleSaveFilePart((TLRPC.TL_upload_saveFilePart) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_upload_saveBigFilePart) {
            handleSaveBigFilePart((TLRPC.TL_upload_saveBigFilePart) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_messages_sendReaction) {
            handleSendReaction((TLRPC.TL_messages_sendReaction) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_messages_updatePinnedMessage) {
            handleUpdatePinnedMessage((TLRPC.TL_messages_updatePinnedMessage) object, onComplete);
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
        } else if (object instanceof TLRPC.TL_messages_editChatTitle) {
            handleEditChatTitle((TLRPC.TL_messages_editChatTitle) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_messages_editChatAbout) {
            handleEditChatAbout((TLRPC.TL_messages_editChatAbout) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_messages_addChatUser) {
            handleAddChatUser((TLRPC.TL_messages_addChatUser) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_messages_deleteChatUser) {
            handleDeleteChatUser((TLRPC.TL_messages_deleteChatUser) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_channels_leaveChannel) {
            handleLeaveChannel((TLRPC.TL_channels_leaveChannel) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_messages_deleteHistory) {
            handleDeleteHistory((TLRPC.TL_messages_deleteHistory) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_account_updateProfile) {
            handleUpdateProfile((TLRPC.TL_account_updateProfile) object, onComplete);
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
            handleGetDialogs((TLRPC.TL_messages_getDialogs) object, onComplete);
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
        } else if (object instanceof TLRPC.TL_upload_getFile) {
            handleGetFile((TLRPC.TL_upload_getFile) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_upload_getWebFile) {
            handleGetWebFile((TLRPC.TL_upload_getWebFile) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_photos_uploadProfilePhoto) {
            handleUploadProfilePhoto((TLRPC.TL_photos_uploadProfilePhoto) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_messages_editChatPhoto) {
            handleEditChatPhoto((TLRPC.TL_messages_editChatPhoto) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_channels_editBanned) {
            handleEditBanned((TLRPC.TL_channels_editBanned) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_auth_logOut) {
            handleLogOut((TLRPC.TL_auth_logOut) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_contacts_resolveUsername) {
            handleResolveUsername((TLRPC.TL_contacts_resolveUsername) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_users_getUsers) {
            handleGetUsers((TLRPC.TL_users_getUsers) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_messages_getMessages) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_messages_messages(), null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_messages_readReactions) {
            if (onComplete != null) {
                TLRPC.TL_messages_affectedHistory aff = new TLRPC.TL_messages_affectedHistory();
                aff.pts = 1;
                aff.pts_count = 1;
                onComplete.run(aff, null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_messages_getRecentReactions) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_messages_reactions(), null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_messages_getCommonChats) {
            handleGetCommonChats((TLRPC.TL_messages_getCommonChats) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_channels_getFullChannel) {
            handleGetFullChannel((TLRPC.TL_channels_getFullChannel) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_channels_getParticipants) {
            handleGetChannelParticipants((TLRPC.TL_channels_getParticipants) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_contacts_getBlocked) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_contacts_blocked(), null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_contacts_getStatuses) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.Vector(), null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_messages_getPinnedDialogs || object instanceof TLRPC.TL_messages_getPeerDialogs) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_messages_peerDialogs(), null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_account_getNotifySettings) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_peerNotifySettings(), null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_account_updateNotifySettings) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_boolTrue(), null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_messages_getDialogFilters) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_messages_dialogFilters(), null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_messages_updateDialogFilter) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_boolTrue(), null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_messages_toggleDialogPin) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_boolTrue(), null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_messages_searchGlobal || object instanceof TLRPC.TL_messages_search) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_messages_messages(), null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_messages_getRecentStickers) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_messages_recentStickers(), null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_messages_getAllStickers) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_messages_allStickers(), null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_messages_getSavedGifs) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_messages_savedGifs(), null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_account_getPrivacy) {
            if (onComplete != null) {
                TLRPC.TL_account_privacyRules rules = new TLRPC.TL_account_privacyRules();
                onComplete.run(rules, null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_account_getAuthorizations) {
            handleGetAuthorizations((TLRPC.TL_account_getAuthorizations) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_account_resetAuthorization) {
            handleResetAuthorization((TLRPC.TL_account_resetAuthorization) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_auth_resetAuthorizations) {
            handleResetAllAuthorizations((TLRPC.TL_auth_resetAuthorizations) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_messages_sendVote) {
            handleSendVote((TLRPC.TL_messages_sendVote) object, onComplete);
            return true;
        } else if (object instanceof TLRPC.TL_messages_saveDraft) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_boolTrue(), null);
            }
            return true;
        } else if (object instanceof TLRPC.TL_messages_getAllDrafts) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_updates(), null);
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
        final String formattedHtml = MatrixTextHelper.entitiesToMatrixHtml(req.message, req.entities);

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
                        doSendMessage(homeserver, accessToken, newRoomId, req.message, formattedHtml, finalReplyEventId, peerDialogId, onComplete);
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

        doSendMessage(homeserver, accessToken, matrixRoomId, req.message, formattedHtml, finalReplyEventId, peerDialogId, onComplete);
    }

    private void doSendMessage(String homeserver, String accessToken, String roomId, String text, String formattedHtml, String replyToEventId, long peerDialogId, RequestDelegate onComplete) {
        String txnId = idMapper.getNextTxnId();

        MatrixClient.MatrixCallback<String> cb = new MatrixClient.MatrixCallback<String>() {
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
        };

        if (!TextUtils.isEmpty(formattedHtml)) {
            MatrixClient.getInstance().sendMessageWithFormattedBody(homeserver, accessToken, roomId, text, formattedHtml, replyToEventId, txnId, cb);
        } else {
            MatrixClient.getInstance().sendMessageWithReply(homeserver, accessToken, roomId, text, replyToEventId, txnId, cb);
        }
    }

    private void handleSaveFilePart(TLRPC.TL_upload_saveFilePart req, RequestDelegate onComplete) {
        if (req.bytes != null && req.bytes.buffer != null) {
            byte[] data = new byte[req.bytes.limit()];
            req.bytes.position(0);
            req.bytes.buffer.get(data);
            filePartBuffers.computeIfAbsent(req.file_id, k -> new TreeMap<>()).put(req.file_part, data);
        }
        if (onComplete != null) {
            onComplete.run(new TLRPC.TL_boolTrue(), null);
        }
    }

    private void handleSaveBigFilePart(TLRPC.TL_upload_saveBigFilePart req, RequestDelegate onComplete) {
        if (req.bytes != null && req.bytes.buffer != null) {
            byte[] data = new byte[req.bytes.limit()];
            req.bytes.position(0);
            req.bytes.buffer.get(data);
            filePartBuffers.computeIfAbsent(req.file_id, k -> new TreeMap<>()).put(req.file_part, data);
        }
        if (onComplete != null) {
            onComplete.run(new TLRPC.TL_boolTrue(), null);
        }
    }

    private void handleSendMedia(TLRPC.TL_messages_sendMedia req, RequestDelegate onComplete) {
        long peerDialogId = DialogObject.getPeerDialogId(req.peer);
        String matrixRoomId = idMapper.getMatrixRoomIdForDialog(peerDialogId);
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        if (req.media instanceof TLRPC.TL_inputMediaGeoPoint) {
            TLRPC.TL_inputMediaGeoPoint geo = (TLRPC.TL_inputMediaGeoPoint) req.media;
            MatrixClient.getInstance().sendLocationMessage(homeserver, accessToken, matrixRoomId, geo.geo_point.lat, geo.geo_point._long, req.message, idMapper.getNextTxnId(), new MatrixClient.MatrixCallback<String>() {
                @Override
                public void onSuccess(String eventId) {
                    TLRPC.TL_updates updates = new TLRPC.TL_updates();
                    updates.date = (int) (System.currentTimeMillis() / 1000);
                    if (onComplete != null) {
                        onComplete.run(updates, null);
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
            return;
        }

        if (req.media instanceof TLRPC.TL_inputMediaPoll) {
            handleSendPoll(matrixRoomId, (TLRPC.TL_inputMediaPoll) req.media, onComplete);
            return;
        }

        long fileId = 0;
        String mimeType = "application/octet-stream";
        String msgType = "m.file";
        String filename = "file";

        if (req.media instanceof TLRPC.TL_inputMediaUploadedPhoto) {
            TLRPC.TL_inputMediaUploadedPhoto photo = (TLRPC.TL_inputMediaUploadedPhoto) req.media;
            fileId = photo.file.id;
            mimeType = "image/jpeg";
            msgType = "m.image";
            filename = "photo.jpg";
        } else if (req.media instanceof TLRPC.TL_inputMediaUploadedDocument) {
            TLRPC.TL_inputMediaUploadedDocument doc = (TLRPC.TL_inputMediaUploadedDocument) req.media;
            fileId = doc.file.id;
            mimeType = doc.mime_type != null ? doc.mime_type : "application/octet-stream";
            boolean isVoice = false;
            if (doc.attributes != null) {
                for (TLRPC.DocumentAttribute attr : doc.attributes) {
                    if (attr instanceof TLRPC.TL_documentAttributeAudio && ((TLRPC.TL_documentAttributeAudio) attr).voice) {
                        isVoice = true;
                        break;
                    }
                }
            }
            if (isVoice) {
                msgType = "m.audio";
                mimeType = "audio/ogg";
                filename = "voice.ogg";
            } else if (mimeType.startsWith("image/")) {
                msgType = "m.image";
            } else if (mimeType.startsWith("audio/")) {
                msgType = "m.audio";
            } else if (mimeType.startsWith("video/")) {
                msgType = "m.video";
            } else {
                msgType = "m.file";
            }
        }

        final long targetFileId = fileId;
        TreeMap<Integer, byte[]> parts = filePartBuffers.remove(fileId);
        byte[] completeFileBytes = assembleBytes(parts);

        final String fMsgType = msgType;
        final String fFilename = filename;
        final String caption = req.message != null ? req.message : "";

        MatrixClient.getInstance().uploadMedia(homeserver, accessToken, completeFileBytes, mimeType, filename, new MatrixClient.MatrixCallback<String>() {
            @Override
            public void onSuccess(String contentUri) {
                if (targetFileId != 0 && !TextUtils.isEmpty(contentUri)) {
                    mediaIdToUrlMap.put(targetFileId, contentUri);
                }
                String txnId = idMapper.getNextTxnId();
                MatrixClient.getInstance().sendMediaMessage(homeserver, accessToken, matrixRoomId, contentUri, fMsgType, fFilename, caption, txnId, new MatrixClient.MatrixCallback<String>() {
                    @Override
                    public void onSuccess(String eventId) {
                        TLRPC.TL_updates updates = new TLRPC.TL_updates();
                        updates.date = (int) (System.currentTimeMillis() / 1000);
                        if (onComplete != null) {
                            onComplete.run(updates, null);
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

    private void handleSendReaction(TLRPC.TL_messages_sendReaction req, RequestDelegate onComplete) {
        long peerDialogId = DialogObject.getPeerDialogId(req.peer);
        String matrixRoomId = idMapper.getMatrixRoomIdForDialog(peerDialogId);
        String eventId = idMapper.getMatrixEventId(matrixRoomId, req.msg_id);
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        String emoji = "👍";
        if (req.reaction != null && !req.reaction.isEmpty()) {
            TLRPC.Reaction r = req.reaction.get(0);
            if (r instanceof TLRPC.TL_reactionEmoji) {
                emoji = ((TLRPC.TL_reactionEmoji) r).emoticon;
            }
        }

        if (!TextUtils.isEmpty(matrixRoomId) && !TextUtils.isEmpty(eventId)) {
            MatrixClient.getInstance().sendReaction(homeserver, accessToken, matrixRoomId, eventId, emoji, idMapper.getNextTxnId(), null);
        }

        if (onComplete != null) {
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            updates.date = (int) (System.currentTimeMillis() / 1000);
            onComplete.run(updates, null);
        }
    }

    private void handleUpdatePinnedMessage(TLRPC.TL_messages_updatePinnedMessage req, RequestDelegate onComplete) {
        long peerDialogId = DialogObject.getPeerDialogId(req.peer);
        String matrixRoomId = idMapper.getMatrixRoomIdForDialog(peerDialogId);
        String eventId = idMapper.getMatrixEventId(matrixRoomId, req.id);
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        if (!TextUtils.isEmpty(matrixRoomId)) {
            try {
                JSONObject body = new JSONObject();
                JSONArray arr = new JSONArray();
                if (!req.unpin && !TextUtils.isEmpty(eventId)) {
                    arr.put(eventId);
                }
                body.put("pinned", arr);
                MatrixClient.getInstance().setRoomState(homeserver, accessToken, matrixRoomId, "m.room.pinned_events", "", body, null);
            } catch (Exception ignore) {}
        }

        if (onComplete != null) {
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            updates.date = (int) (System.currentTimeMillis() / 1000);
            onComplete.run(updates, null);
        }
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
        String fromToken = roomPrevBatchMap.get(matrixRoomId);

        MatrixClient.getInstance().loadRoomMessages(homeserver, accessToken, matrixRoomId, fromToken, req.limit > 0 ? req.limit : 30, new MatrixClient.MatrixCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject json) {
                String endToken = json.optString("end", null);
                if (!TextUtils.isEmpty(endToken)) {
                    roomPrevBatchMap.put(matrixRoomId, endToken);
                }

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
                        TLRPC.TL_message msg = parseMatrixMessageEvent(matrixRoomId, ev, peerDialogId, dialogPeer, myMatrixId);
                        if (msg != null) {
                            String senderId = ev.optString("sender");
                            long senderNumericId = idMapper.getNumericUserId(senderId);
                            TLRPC.TL_user senderUser = createTLUser(senderNumericId, senderId, idMapper.getUserDisplayName(senderNumericId), idMapper.getUserAvatarUrl(senderNumericId), senderId.equals(myMatrixId));
                            messagesRes.users.add(senderUser);
                            messagesRes.messages.add(msg);
                        }
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
                MatrixClient.getInstance().setReadMarkers(homeserver, accessToken, matrixRoomId, eventId, eventId, null);
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

    private void handleEditChatTitle(TLRPC.TL_messages_editChatTitle req, RequestDelegate onComplete) {
        String matrixRoomId = idMapper.getMatrixRoomId(req.chat_id);
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        if (!TextUtils.isEmpty(matrixRoomId)) {
            try {
                JSONObject body = new JSONObject();
                body.put("name", req.title);
                MatrixClient.getInstance().setRoomState(homeserver, accessToken, matrixRoomId, "m.room.name", "", body, null);
            } catch (Exception ignore) {}
        }

        if (onComplete != null) {
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            updates.date = (int) (System.currentTimeMillis() / 1000);
            onComplete.run(updates, null);
        }
    }

    private void handleEditChatAbout(TLRPC.TL_messages_editChatAbout req, RequestDelegate onComplete) {
        long peerDialogId = DialogObject.getPeerDialogId(req.peer);
        String matrixRoomId = idMapper.getMatrixRoomIdForDialog(peerDialogId);
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        if (!TextUtils.isEmpty(matrixRoomId)) {
            try {
                JSONObject body = new JSONObject();
                body.put("topic", req.about);
                MatrixClient.getInstance().setRoomState(homeserver, accessToken, matrixRoomId, "m.room.topic", "", body, null);
            } catch (Exception ignore) {}
        }

        if (onComplete != null) {
            onComplete.run(new TLRPC.TL_boolTrue(), null);
        }
    }

    private void handleAddChatUser(TLRPC.TL_messages_addChatUser req, RequestDelegate onComplete) {
        String matrixRoomId = idMapper.getMatrixRoomId(req.chat_id);
        String targetUserId = idMapper.getMatrixUserId(req.user_id != null ? req.user_id.user_id : 0);
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        if (!TextUtils.isEmpty(matrixRoomId) && !TextUtils.isEmpty(targetUserId)) {
            MatrixClient.getInstance().inviteUser(homeserver, accessToken, matrixRoomId, targetUserId, null);
        }

        if (onComplete != null) {
            TLRPC.TL_messages_invitedUsers res = new TLRPC.TL_messages_invitedUsers();
            onComplete.run(res, null);
        }
    }

    private void handleDeleteChatUser(TLRPC.TL_messages_deleteChatUser req, RequestDelegate onComplete) {
        String matrixRoomId = idMapper.getMatrixRoomId(req.chat_id);
        String targetUserId = idMapper.getMatrixUserId(req.user_id != null ? req.user_id.user_id : 0);
        String myMatrixId = MatrixConfig.getUserId(currentAccount);
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        if (!TextUtils.isEmpty(matrixRoomId)) {
            if (targetUserId != null && targetUserId.equals(myMatrixId)) {
                MatrixClient.getInstance().leaveRoom(homeserver, accessToken, matrixRoomId, null);
            } else if (!TextUtils.isEmpty(targetUserId)) {
                MatrixClient.getInstance().kickUser(homeserver, accessToken, matrixRoomId, targetUserId, "Kicked by admin", null);
            }
        }

        if (onComplete != null) {
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            updates.date = (int) (System.currentTimeMillis() / 1000);
            onComplete.run(updates, null);
        }
    }

    private void handleLeaveChannel(TLRPC.TL_channels_leaveChannel req, RequestDelegate onComplete) {
        long chatId = req.channel != null ? req.channel.channel_id : 0;
        String matrixRoomId = idMapper.getMatrixRoomId(chatId);
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        if (!TextUtils.isEmpty(matrixRoomId)) {
            MatrixClient.getInstance().leaveRoom(homeserver, accessToken, matrixRoomId, null);
        }

        if (onComplete != null) {
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            updates.date = (int) (System.currentTimeMillis() / 1000);
            onComplete.run(updates, null);
        }
    }

    private void handleDeleteHistory(TLRPC.TL_messages_deleteHistory req, RequestDelegate onComplete) {
        long peerDialogId = DialogObject.getPeerDialogId(req.peer);
        String matrixRoomId = idMapper.getMatrixRoomIdForDialog(peerDialogId);
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        if (!TextUtils.isEmpty(matrixRoomId)) {
            MatrixClient.getInstance().leaveRoom(homeserver, accessToken, matrixRoomId, null);
            MatrixClient.getInstance().forgetRoom(homeserver, accessToken, matrixRoomId, null);
        }

        if (onComplete != null) {
            TLRPC.TL_messages_affectedHistory affected = new TLRPC.TL_messages_affectedHistory();
            affected.pts = 1;
            affected.pts_count = 1;
            affected.offset = 0;
            onComplete.run(affected, null);
        }
    }

    private void handleUpdateProfile(TLRPC.TL_account_updateProfile req, RequestDelegate onComplete) {
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);
        String myMatrixId = MatrixConfig.getUserId(currentAccount);

        String fullName = (req.first_name != null ? req.first_name : "") + (req.last_name != null && !req.last_name.isEmpty() ? " " + req.last_name : "");
        fullName = fullName.trim();

        if (!TextUtils.isEmpty(fullName)) {
            MatrixConfig.setDisplayName(currentAccount, fullName);
            MatrixClient.getInstance().updateProfileDisplayName(homeserver, accessToken, myMatrixId, fullName, null);
        }

        TLRPC.User selfUser = UserConfig.getInstance(currentAccount).getCurrentUser();
        if (selfUser != null) {
            selfUser.first_name = req.first_name;
            selfUser.last_name = req.last_name;
            UserConfig.getInstance(currentAccount).saveConfig(true);
        }

        if (onComplete != null) {
            onComplete.run(selfUser, null);
        }
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

                // Also search public rooms
                MatrixClient.getInstance().searchPublicRooms(homeserver, accessToken, query, 10, new MatrixClient.MatrixCallback<List<MatrixClient.PublicRoom>>() {
                    @Override
                    public void onSuccess(List<MatrixClient.PublicRoom> rooms) {
                        for (MatrixClient.PublicRoom pr : rooms) {
                            long chatId = idMapper.getNumericChatId(pr.roomId);
                            TLRPC.TL_chat chat = createTLChat(chatId, !TextUtils.isEmpty(pr.name) ? pr.name : pr.roomId, pr.topic, Math.max(pr.numJoinedMembers, 2), pr.avatarUrl);
                            MessagesController.getInstance(currentAccount).putChat(chat, false);
                            found.chats.add(chat);
                            TLRPC.TL_peerChat peer = new TLRPC.TL_peerChat();
                            peer.chat_id = chatId;
                            found.results.add(peer);
                        }
                        if (onComplete != null) {
                            onComplete.run(found, null);
                        }
                    }

                    @Override
                    public void onError(int code, String message) {
                        if (onComplete != null) {
                            onComplete.run(found, null);
                        }
                    }
                });
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

    private void handleGetCommonChats(TLRPC.TL_messages_getCommonChats req, RequestDelegate onComplete) {
        long userId = req.user_id != null ? req.user_id.user_id : 0;
        String matrixUserId = idMapper.getMatrixUserId(userId);

        if (TextUtils.isEmpty(matrixUserId)) {
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_messages_chats(), null);
            }
            return;
        }

        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        MatrixClient.getInstance().getMutualRooms(homeserver, accessToken, matrixUserId, new MatrixClient.MatrixCallback<List<String>>() {
            @Override
            public void onSuccess(List<String> roomIds) {
                TLRPC.TL_messages_chats res = new TLRPC.TL_messages_chats();
                if (roomIds != null) {
                    for (String rId : roomIds) {
                        long cId = idMapper.getNumericChatId(rId);
                        TLRPC.Chat c = MessagesController.getInstance(currentAccount).getChat(cId);
                        if (c != null) {
                            res.chats.add(c);
                        } else {
                            res.chats.add(createTLChat(cId, "Matrix Group " + cId, "", 2));
                        }
                    }
                }
                if (onComplete != null) {
                    onComplete.run(res, null);
                }
            }

            @Override
            public void onError(int code, String message) {
                if (onComplete != null) {
                    onComplete.run(new TLRPC.TL_messages_chats(), null);
                }
            }
        });
    }

    private void handleGetFullChannel(TLRPC.TL_channels_getFullChannel req, RequestDelegate onComplete) {
        long chatId = req.channel != null ? req.channel.channel_id : 0;
        String roomId = idMapper.getMatrixRoomId(chatId);

        TLRPC.TL_messages_chatFull chatFull = new TLRPC.TL_messages_chatFull();
        TLRPC.TL_channelFull full = new TLRPC.TL_channelFull();
        full.id = chatId;
        full.about = !TextUtils.isEmpty(roomId) ? roomId : "Matrix Room";
        full.notify_settings = new TLRPC.TL_peerNotifySettings();
        chatFull.full_chat = full;

        TLRPC.Chat c = MessagesController.getInstance(currentAccount).getChat(chatId);
        if (c != null) {
            chatFull.chats.add(c);
        }

        if (onComplete != null) {
            onComplete.run(chatFull, null);
        }
    }

    private void handleGetChannelParticipants(TLRPC.TL_channels_getParticipants req, RequestDelegate onComplete) {
        TLRPC.TL_channels_channelParticipants res = new TLRPC.TL_channels_channelParticipants();
        res.count = 0;
        if (onComplete != null) {
            onComplete.run(res, null);
        }
    }

    private void handleGetDialogs(TLRPC.TL_messages_getDialogs req, RequestDelegate onComplete) {
        if (onComplete != null) {
            AndroidUtilities.runOnUIThread(() -> {
                ArrayList<TLRPC.Dialog> all = MessagesController.getInstance(currentAccount).getAllDialogs();
                if (all != null && !all.isEmpty()) {
                    TLRPC.TL_messages_dialogs dialogsRes = new TLRPC.TL_messages_dialogs();
                    dialogsRes.dialogs.addAll(all);
                    for (TLRPC.Dialog d : all) {
                        if (d.id > 0) {
                            TLRPC.User u = MessagesController.getInstance(currentAccount).getUser(d.id);
                            if (u != null) {
                                dialogsRes.users.add(u);
                            }
                        } else if (d.id < 0) {
                            TLRPC.Chat c = MessagesController.getInstance(currentAccount).getChat(-d.id);
                            if (c != null) {
                                dialogsRes.chats.add(c);
                            }
                        }
                    }
                    cachedDialogs = dialogsRes;
                    onComplete.run(dialogsRes, null);
                } else if (cachedDialogs != null && !cachedDialogs.dialogs.isEmpty()) {
                    onComplete.run(cachedDialogs, null);
                } else {
                    TLRPC.TL_error err = new TLRPC.TL_error();
                    err.code = 500;
                    err.text = "SYNC_PENDING";
                    onComplete.run(null, err);
                    scheduleSync(0);
                }
            });
        }
    }

    private void handleGetFile(TLRPC.TL_upload_getFile req, RequestDelegate onComplete) {
        long mediaId = 0;
        if (req.location instanceof TLRPC.TL_inputPhotoFileLocation) {
            mediaId = ((TLRPC.TL_inputPhotoFileLocation) req.location).id;
        } else if (req.location instanceof TLRPC.TL_inputDocumentFileLocation) {
            mediaId = ((TLRPC.TL_inputDocumentFileLocation) req.location).id;
        } else if (req.location instanceof TLRPC.TL_inputPeerPhotoFileLocation) {
            mediaId = ((TLRPC.TL_inputPeerPhotoFileLocation) req.location).photo_id;
        } else if (req.location instanceof TLRPC.TL_inputFileLocation) {
            mediaId = ((TLRPC.TL_inputFileLocation) req.location).volume_id;
        }

        String mxcUrl = mediaIdToUrlMap.get(mediaId);
        if (TextUtils.isEmpty(mxcUrl)) {
            mxcUrl = idMapper.getUserAvatarUrl(mediaId);
        }

        if (TextUtils.isEmpty(mxcUrl)) {
            if (onComplete != null) {
                TLRPC.TL_error err = new TLRPC.TL_error();
                err.code = 404;
                err.text = "FILE_NOT_FOUND";
                onComplete.run(null, err);
            }
            return;
        }

        File cacheFile = getFileForMxc(mxcUrl);
        if (cacheFile != null && cacheFile.exists() && cacheFile.length() > 0) {
            serveFileChunk(cacheFile, req.offset, req.limit, onComplete);
            return;
        }

        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        MatrixClient.getInstance().downloadMedia(homeserver, accessToken, mxcUrl, new MatrixClient.MatrixCallback<byte[]>() {
            @Override
            public void onSuccess(byte[] bytes) {
                try {
                    if (cacheFile != null) {
                        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                            fos.write(bytes);
                            fos.flush();
                        }
                        serveFileChunk(cacheFile, req.offset, req.limit, onComplete);
                        return;
                    }
                } catch (Exception ignore) {}

                serveMemoryChunk(bytes, req.offset, req.limit, onComplete);
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

    private void handleGetWebFile(TLRPC.TL_upload_getWebFile req, RequestDelegate onComplete) {
        if (onComplete != null) {
            TLRPC.TL_upload_webFile webFile = new TLRPC.TL_upload_webFile();
            webFile.size = 0;
            webFile.mime_type = "application/octet-stream";
            webFile.file_type = new TLRPC.TL_storage_filePartial();
            webFile.mtime = (int) (System.currentTimeMillis() / 1000);
            try {
                webFile.bytes = new NativeByteBuffer(0);
            } catch (Exception ignore) {}
            onComplete.run(webFile, null);
        }
    }

    private void serveFileChunk(File file, long offset, int limit, RequestDelegate onComplete) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long fileLen = file.length();
            if (offset >= fileLen) {
                TLRPC.TL_upload_file emptyFile = new TLRPC.TL_upload_file();
                emptyFile.type = new TLRPC.TL_storage_filePartial();
                emptyFile.mtime = (int) (System.currentTimeMillis() / 1000);
                emptyFile.bytes = new NativeByteBuffer(0);
                if (onComplete != null) onComplete.run(emptyFile, null);
                return;
            }

            int toRead = (int) Math.min(limit, fileLen - offset);
            byte[] chunk = new byte[toRead];
            raf.seek(offset);
            raf.readFully(chunk);

            TLRPC.TL_upload_file res = new TLRPC.TL_upload_file();
            res.type = new TLRPC.TL_storage_filePartial();
            res.mtime = (int) (System.currentTimeMillis() / 1000);
            NativeByteBuffer buf = new NativeByteBuffer(chunk.length);
            buf.writeBytes(chunk);
            buf.position(0);
            res.bytes = buf;
            if (onComplete != null) onComplete.run(res, null);
        } catch (Exception e) {
            FileLog.e("serveFileChunk error", e);
            if (onComplete != null) {
                TLRPC.TL_error err = new TLRPC.TL_error();
                err.code = 500;
                err.text = e.getMessage();
                onComplete.run(null, err);
            }
        }
    }

    private void serveMemoryChunk(byte[] allBytes, long offset, int limit, RequestDelegate onComplete) {
        try {
            if (allBytes == null || offset >= allBytes.length) {
                TLRPC.TL_upload_file emptyFile = new TLRPC.TL_upload_file();
                emptyFile.type = new TLRPC.TL_storage_filePartial();
                emptyFile.mtime = (int) (System.currentTimeMillis() / 1000);
                emptyFile.bytes = new NativeByteBuffer(0);
                if (onComplete != null) onComplete.run(emptyFile, null);
                return;
            }
            int toRead = (int) Math.min(limit, allBytes.length - offset);
            TLRPC.TL_upload_file res = new TLRPC.TL_upload_file();
            res.type = new TLRPC.TL_storage_filePartial();
            res.mtime = (int) (System.currentTimeMillis() / 1000);
            NativeByteBuffer buf = new NativeByteBuffer(toRead);
            buf.writeBytes(allBytes, (int) offset, toRead);
            buf.position(0);
            res.bytes = buf;
            if (onComplete != null) onComplete.run(res, null);
        } catch (Exception e) {
            FileLog.e("serveMemoryChunk error", e);
            if (onComplete != null) {
                TLRPC.TL_error err = new TLRPC.TL_error();
                err.code = 500;
                err.text = e.getMessage();
                onComplete.run(null, err);
            }
        }
    }

    private void handleUploadProfilePhoto(TLRPC.TL_photos_uploadProfilePhoto req, RequestDelegate onComplete) {
        long fileId = req.file != null ? req.file.id : 0;
        TreeMap<Integer, byte[]> parts = filePartBuffers.remove(fileId);
        byte[] completeBytes = assembleBytes(parts);

        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);
        String myMatrixId = MatrixConfig.getUserId(currentAccount);

        if (completeBytes.length > 0) {
            MatrixClient.getInstance().uploadMedia(homeserver, accessToken, completeBytes, "image/jpeg", "avatar.jpg", new MatrixClient.MatrixCallback<String>() {
                @Override
                public void onSuccess(String contentUri) {
                    MatrixClient.getInstance().updateProfileAvatar(homeserver, accessToken, myMatrixId, contentUri, null);

                    long myNumericId = UserConfig.getInstance(currentAccount).getClientUserId();
                    idMapper.setUserNameAndAvatar(myNumericId, MatrixConfig.getDisplayName(currentAccount), contentUri);
                    mediaIdToUrlMap.put(myNumericId, contentUri);

                    TLRPC.User selfUser = UserConfig.getInstance(currentAccount).getCurrentUser();
                    if (selfUser != null) {
                        selfUser.flags |= 32;
                        selfUser.photo = createProfilePhoto(myNumericId);
                        UserConfig.getInstance(currentAccount).saveConfig(true);
                    }

                    AndroidUtilities.runOnUIThread(() -> {
                        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
                        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
                    });

                    TLRPC.TL_photos_photo photoRes = new TLRPC.TL_photos_photo();
                    TLRPC.TL_photo photo = new TLRPC.TL_photo();
                    photo.id = myNumericId;
                    photo.access_hash = myNumericId;
                    photo.date = (int) (System.currentTimeMillis() / 1000);
                    photoRes.photo = photo;
                    if (selfUser != null) {
                        photoRes.users.add(selfUser);
                    }
                    if (onComplete != null) {
                        onComplete.run(photoRes, null);
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
                TLRPC.TL_photos_photo photoRes = new TLRPC.TL_photos_photo();
                onComplete.run(photoRes, null);
            }
        }
    }

    private void handleEditChatPhoto(TLRPC.TL_messages_editChatPhoto req, RequestDelegate onComplete) {
        long fileId = 0;
        if (req.photo instanceof TLRPC.TL_inputChatUploadedPhoto) {
            fileId = ((TLRPC.TL_inputChatUploadedPhoto) req.photo).file.id;
        }
        TreeMap<Integer, byte[]> parts = filePartBuffers.remove(fileId);
        byte[] completeBytes = assembleBytes(parts);

        String matrixRoomId = idMapper.getMatrixRoomId(req.chat_id);
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        if (!TextUtils.isEmpty(matrixRoomId) && completeBytes.length > 0) {
            MatrixClient.getInstance().uploadMedia(homeserver, accessToken, completeBytes, "image/jpeg", "chat_avatar.jpg", new MatrixClient.MatrixCallback<String>() {
                @Override
                public void onSuccess(String contentUri) {
                    try {
                        JSONObject body = new JSONObject();
                        body.put("url", contentUri);
                        MatrixClient.getInstance().setRoomState(homeserver, accessToken, matrixRoomId, "m.room.avatar", "", body, null);
                    } catch (Exception ignore) {}

                    mediaIdToUrlMap.put(req.chat_id, contentUri);

                    TLRPC.TL_updates updates = new TLRPC.TL_updates();
                    updates.date = (int) (System.currentTimeMillis() / 1000);
                    if (onComplete != null) {
                        onComplete.run(updates, null);
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
        } else {
            if (onComplete != null) {
                TLRPC.TL_updates updates = new TLRPC.TL_updates();
                updates.date = (int) (System.currentTimeMillis() / 1000);
                onComplete.run(updates, null);
            }
        }
    }

    private void handleEditBanned(TLRPC.TL_channels_editBanned req, RequestDelegate onComplete) {
        long chatId = req.channel != null ? req.channel.channel_id : 0;
        String roomId = idMapper.getMatrixRoomId(chatId);
        long userId = req.participant != null ? DialogObject.getPeerDialogId(req.participant) : 0;
        String matrixUserId = idMapper.getMatrixUserId(userId);
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        boolean isBanning = req.banned_rights != null && req.banned_rights.view_messages;

        if (!TextUtils.isEmpty(roomId) && !TextUtils.isEmpty(matrixUserId)) {
            if (isBanning) {
                MatrixClient.getInstance().banUser(homeserver, accessToken, roomId, matrixUserId, "Banned by admin", null);
            } else {
                MatrixClient.getInstance().unbanUser(homeserver, accessToken, roomId, matrixUserId, null);
            }
        }

        if (onComplete != null) {
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            updates.date = (int) (System.currentTimeMillis() / 1000);
            onComplete.run(updates, null);
        }
    }

    private void handleLogOut(TLRPC.TL_auth_logOut req, RequestDelegate onComplete) {
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);
        MatrixClient.getInstance().logout(homeserver, accessToken, null);
        stop();
        MatrixConfig.clear(currentAccount);
        if (onComplete != null) {
            onComplete.run(new TLRPC.TL_boolTrue(), null);
        }
    }

    private void handleResolveUsername(TLRPC.TL_contacts_resolveUsername req, RequestDelegate onComplete) {
        String username = req.username != null ? req.username.trim() : "";
        String query = username;
        if (!query.startsWith("@")) {
            query = "@" + query;
        }
        if (query.contains("_") && !query.contains(":")) {
            query = query.replaceFirst("_", ":");
        }

        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        MatrixClient.getInstance().getUserProfile(homeserver, accessToken, query, new MatrixClient.MatrixCallback<MatrixClient.MatrixUser>() {
            @Override
            public void onSuccess(MatrixClient.MatrixUser mu) {
                TLRPC.TL_contacts_resolvedPeer resolved = new TLRPC.TL_contacts_resolvedPeer();
                long uid = idMapper.getNumericUserId(mu.userId);
                idMapper.setUserNameAndAvatar(uid, mu.displayName, mu.avatarUrl);
                TLRPC.TL_user tu = createTLUser(uid, mu.userId, mu.displayName, mu.avatarUrl, false);
                MessagesController.getInstance(currentAccount).putUser(tu, false);

                TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
                peer.user_id = uid;
                resolved.peer = peer;
                resolved.users.add(tu);
                if (onComplete != null) {
                    onComplete.run(resolved, null);
                }
            }

            @Override
            public void onError(int code, String message) {
                if (onComplete != null) {
                    TLRPC.TL_error err = new TLRPC.TL_error();
                    err.code = 404;
                    err.text = "USERNAME_NOT_OCCUPIED";
                    onComplete.run(null, err);
                }
            }
        });
    }

    private void handleGetUsers(TLRPC.TL_users_getUsers req, RequestDelegate onComplete) {
        TLRPC.Vector res = new TLRPC.Vector();
        if (req.id != null) {
            for (TLRPC.InputUser iu : req.id) {
                TLRPC.User u = MessagesController.getInstance(currentAccount).getUser(iu.user_id);
                if (u != null) {
                    res.objects.add(u);
                }
            }
        }
        if (onComplete != null) {
            onComplete.run(res, null);
        }
    }

    private File getMediaCacheDir() {
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "matrix_media");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private File getFileForMxc(String mxcUrl) {
        if (TextUtils.isEmpty(mxcUrl) || !mxcUrl.startsWith("mxc://")) {
            return null;
        }
        String path = mxcUrl.substring(6);
        String safeName = path.replace('/', '_');
        return new File(getMediaCacheDir(), safeName);
    }

    private byte[] assembleBytes(TreeMap<Integer, byte[]> parts) {
        if (parts == null || parts.isEmpty()) {
            return new byte[0];
        }
        int totalLen = 0;
        for (byte[] p : parts.values()) {
            totalLen += p.length;
        }
        byte[] completeBytes = new byte[totalLen];
        int offset = 0;
        for (byte[] p : parts.values()) {
            System.arraycopy(p, 0, completeBytes, offset, p.length);
            offset += p.length;
        }
        return completeBytes;
    }

    private TLRPC.TL_userProfilePhoto createProfilePhoto(long numericId) {
        TLRPC.TL_userProfilePhoto photo = new TLRPC.TL_userProfilePhoto();
        photo.photo_id = numericId;
        TLRPC.TL_fileLocationToBeDeprecated locSmall = new TLRPC.TL_fileLocationToBeDeprecated();
        locSmall.volume_id = numericId;
        locSmall.local_id = 1;
        photo.photo_small = locSmall;
        TLRPC.TL_fileLocationToBeDeprecated locBig = new TLRPC.TL_fileLocationToBeDeprecated();
        locBig.volume_id = numericId;
        locBig.local_id = 2;
        photo.photo_big = locBig;
        return photo;
    }

    private TLRPC.TL_chatPhoto createChatPhoto(long chatId) {
        TLRPC.TL_chatPhoto photo = new TLRPC.TL_chatPhoto();
        photo.photo_id = chatId;
        TLRPC.TL_fileLocationToBeDeprecated locSmall = new TLRPC.TL_fileLocationToBeDeprecated();
        locSmall.volume_id = chatId;
        locSmall.local_id = 1;
        photo.photo_small = locSmall;
        TLRPC.TL_fileLocationToBeDeprecated locBig = new TLRPC.TL_fileLocationToBeDeprecated();
        locBig.volume_id = chatId;
        locBig.local_id = 2;
        photo.photo_big = locBig;
        return photo;
    }

    private TLRPC.TL_message parseMatrixMessageEvent(String roomId, JSONObject ev, long peerDialogId, TLRPC.Peer dialogPeer, String myMatrixId) {
        if (ev == null) {
            return null;
        }
        String type = ev.optString("type");
        if (!"m.room.message".equals(type) && !"m.room.encrypted".equals(type) && !"org.matrix.msc3381.poll.start".equals(type) && !"m.poll.start".equals(type)) {
            return null;
        }
        JSONObject content = ev.optJSONObject("content");
        if (content == null) return null;

        String eventId = ev.optString("event_id");
        String senderId = ev.optString("sender");
        long originServerTs = ev.optLong("origin_server_ts", System.currentTimeMillis());

        int msgId = idMapper.getNumericMessageId(roomId, eventId);
        long senderNumericId = idMapper.getNumericUserId(senderId);
        boolean isOut = senderId != null && senderId.equals(myMatrixId);

        // Handle Polls (MSC3381 / org.matrix.msc3381.poll.start & m.poll.start)
        if ("org.matrix.msc3381.poll.start".equals(type) || "m.poll.start".equals(type)) {
            JSONObject pollStart = content.optJSONObject("org.matrix.msc3381.poll.start");
            if (pollStart == null) {
                pollStart = content.optJSONObject("m.poll.start");
            }
            String question = "";
            if (pollStart != null) {
                JSONObject qObj = pollStart.optJSONObject("question");
                if (qObj != null) {
                    question = qObj.optString("org.matrix.msc1767.text", "");
                }
            }
            if (TextUtils.isEmpty(question)) {
                question = content.optString("org.matrix.msc1767.text", "Poll");
            }

            TLRPC.TL_messageMediaPoll mediaPoll = new TLRPC.TL_messageMediaPoll();
            mediaPoll.poll = new TLRPC.TL_poll();
            mediaPoll.poll.id = Math.abs((long) eventId.hashCode());
            mediaPoll.poll.question = new TLRPC.TL_textWithEntities();
            mediaPoll.poll.question.text = question;
            mediaPoll.poll.flags = 0;
            if (pollStart != null && pollStart.optInt("max_selections", 1) > 1) {
                mediaPoll.poll.multiple_choice = true;
                mediaPoll.poll.flags |= 4;
            }

            mediaPoll.results = new TLRPC.TL_pollResults();
            mediaPoll.results.flags = 0;

            if (pollStart != null) {
                JSONArray answers = pollStart.optJSONArray("answers");
                if (answers != null) {
                    for (int i = 0; i < answers.length(); i++) {
                        JSONObject a = answers.optJSONObject(i);
                        if (a != null) {
                            String aId = a.optString("id", "ans_" + i);
                            String aText = a.optString("org.matrix.msc1767.text", "Option " + (i + 1));

                            TLRPC.TL_pollAnswer answer = new TLRPC.TL_pollAnswer();
                            answer.text = new TLRPC.TL_textWithEntities();
                            answer.text.text = aText;
                            answer.option = aId.getBytes(StandardCharsets.UTF_8);
                            mediaPoll.poll.answers.add(answer);

                            TLRPC.TL_pollAnswerVoters voter = new TLRPC.TL_pollAnswerVoters();
                            voter.option = answer.option;
                            voter.voters = 0;
                            mediaPoll.results.results.add(voter);
                        }
                    }
                }
            }

            TLRPC.TL_message msg = new TLRPC.TL_message();
            msg.id = msgId;
            msg.date = (int) (originServerTs / 1000);
            msg.message = question;
            msg.media = mediaPoll;
            msg.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID | TLRPC.MESSAGE_FLAG_HAS_MEDIA;

            TLRPC.TL_peerUser fromPeer = new TLRPC.TL_peerUser();
            fromPeer.user_id = senderNumericId;
            msg.from_id = fromPeer;
            msg.peer_id = dialogPeer;
            msg.dialog_id = peerDialogId;

            if (isOut) {
                msg.out = true;
                msg.flags |= 2;
            }
            msg.send_state = 0;
            return msg;
        }

        // Handle Encrypted Matrix Message (m.room.encrypted)
        if ("m.room.encrypted".equals(type)) {
            String sessionId = content.optString("session_id", "");
            boolean hasKey = MatrixKeyManager.getInstance(currentAccount).hasRoomKey(roomId, sessionId);

            TLRPC.TL_message msg = new TLRPC.TL_message();
            msg.id = msgId;
            msg.date = (int) (originServerTs / 1000);
            if (hasKey) {
                msg.message = "🔒 [پیام رمزگذاری‌شده ماتریکس - کلید دریافت شد]";
            } else {
                msg.message = "🔐 [پیام رمزگذاری‌شده ماتریکس / Encrypted Message]";
            }
            msg.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID;

            TLRPC.TL_peerUser fromPeer = new TLRPC.TL_peerUser();
            fromPeer.user_id = senderNumericId;
            msg.from_id = fromPeer;
            msg.peer_id = dialogPeer;
            msg.dialog_id = peerDialogId;

            if (isOut) {
                msg.out = true;
                msg.flags |= 2;
            }
            msg.send_state = 0;
            return msg;
        }

        String body = content.optString("body", "");
        String msgType = content.optString("msgtype", "m.text");

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

        if (isOut) {
            msg.out = true;
            msg.flags |= 2;
        }
        msg.send_state = 0; // Sent

        // Check for formatted HTML body
        if ("org.matrix.custom.html".equals(content.optString("format"))) {
            String formattedBody = content.optString("formatted_body");
            MatrixTextHelper.ParsedMatrixText parsed = MatrixTextHelper.parseMatrixHtml(formattedBody);
            if (!TextUtils.isEmpty(parsed.plainText)) {
                msg.message = parsed.plainText;
            }
            if (parsed.entities != null && !parsed.entities.isEmpty()) {
                msg.entities = parsed.entities;
                msg.flags |= TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
            }
        }

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

        // Check for location
        if ("m.location".equals(msgType)) {
            String geoUri = content.optString("geo_uri", "");
            if (geoUri.startsWith("geo:")) {
                try {
                    String coords = geoUri.substring(4);
                    int comma = coords.indexOf(',');
                    if (comma > 0) {
                        double lat = Double.parseDouble(coords.substring(0, comma).trim());
                        int sc = coords.indexOf(';', comma);
                        String lonStr = sc > 0 ? coords.substring(comma + 1, sc).trim() : coords.substring(comma + 1).trim();
                        double lon = Double.parseDouble(lonStr);

                        TLRPC.TL_messageMediaGeo mediaGeo = new TLRPC.TL_messageMediaGeo();
                        mediaGeo.geo = new TLRPC.TL_geoPoint();
                        mediaGeo.geo.lat = lat;
                        mediaGeo.geo._long = lon;
                        msg.media = mediaGeo;
                        msg.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
                    }
                } catch (Exception ignore) {}
            }
        }

        // Map media content URI
        String mediaUrl = content.optString("url");
        if (!TextUtils.isEmpty(mediaUrl)) {
            mediaIdToUrlMap.put((long) msgId, mediaUrl);
        }

        // Handle media representations
        if ("m.image".equals(msgType)) {
            TLRPC.TL_messageMediaPhoto mediaPhoto = new TLRPC.TL_messageMediaPhoto();
            TLRPC.TL_photo photo = new TLRPC.TL_photo();
            photo.id = msgId;
            photo.access_hash = msgId;
            photo.file_reference = new byte[0];
            photo.date = msg.date;

            TLRPC.TL_photoSize photoSize = new TLRPC.TL_photoSize();
            photoSize.type = "x";
            photoSize.w = 800;
            photoSize.h = 600;
            photoSize.size = 100000;
            TLRPC.TL_fileLocationToBeDeprecated sizeLoc = new TLRPC.TL_fileLocationToBeDeprecated();
            sizeLoc.volume_id = msgId;
            sizeLoc.local_id = 1;
            photoSize.location = sizeLoc;

            photo.sizes.add(photoSize);
            mediaPhoto.photo = photo;
            msg.media = mediaPhoto;
            msg.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
        } else if ("m.audio".equals(msgType) || "m.video".equals(msgType) || "m.file".equals(msgType) || "m.sticker".equals(msgType)) {
            TLRPC.TL_messageMediaDocument mediaDoc = new TLRPC.TL_messageMediaDocument();
            TLRPC.TL_document doc = new TLRPC.TL_document();
            doc.id = msgId;
            doc.access_hash = msgId;
            doc.file_reference = new byte[0];
            doc.date = msg.date;
            doc.mime_type = "m.audio".equals(msgType) ? "audio/ogg" : ("m.video".equals(msgType) ? "video/mp4" : ("m.sticker".equals(msgType) ? "image/webp" : "application/octet-stream"));
            doc.size = 100000;
            doc.dc_id = 1;

            if ("m.audio".equals(msgType)) {
                TLRPC.TL_documentAttributeAudio audioAttr = new TLRPC.TL_documentAttributeAudio();
                if (content.has("org.matrix.msc3245.voice")) {
                    audioAttr.voice = true;
                }
                doc.attributes.add(audioAttr);
            } else if ("m.sticker".equals(msgType)) {
                TLRPC.TL_documentAttributeSticker stickerAttr = new TLRPC.TL_documentAttributeSticker();
                doc.attributes.add(stickerAttr);
            }

            mediaDoc.document = doc;
            msg.media = mediaDoc;
            msg.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
        }

        return msg;
    }

    private TLRPC.TL_user createTLUser(long numericId, String matrixUserId, String displayName, String avatarUrl, boolean isSelf) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = numericId;
        user.access_hash = numericId != 0 ? numericId : 1;
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
        if (isSelf) {
            user.premium = true;
            user.flags |= 268435456;
        }
        if (!TextUtils.isEmpty(user.last_name)) {
            user.flags |= 4;
        }
        if (!TextUtils.isEmpty(avatarUrl)) {
            user.flags |= 32;
            user.photo = createProfilePhoto(numericId);
            mediaIdToUrlMap.put(numericId, avatarUrl);
        }
        user.status = new TLRPC.TL_userStatusOnline();
        user.status.expires = (int) (System.currentTimeMillis() / 1000) + 300;
        return user;
    }

    private TLRPC.TL_chat createTLChat(long chatId, String title, String topic, int memberCount, String avatarUrl) {
        TLRPC.TL_chat chat = new TLRPC.TL_chat();
        chat.id = chatId;
        chat.title = title;
        chat.participants_count = memberCount;
        chat.date = (int) (System.currentTimeMillis() / 1000);
        if (!TextUtils.isEmpty(avatarUrl)) {
            chat.flags |= 16;
            chat.photo = createChatPhoto(chatId);
            mediaIdToUrlMap.put(chatId, avatarUrl);
        }
        return chat;
    }

    private TLRPC.TL_chat createTLChat(long chatId, String title, String topic, int memberCount) {
        return createTLChat(chatId, title, topic, memberCount, null);
    }

    // ---------------- Devices & Active Sessions ----------------

    private void handleGetAuthorizations(TLRPC.TL_account_getAuthorizations req, RequestDelegate onComplete) {
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);
        String currentDeviceId = MatrixConfig.getDeviceId(currentAccount);

        MatrixClient.getInstance().getDevices(homeserver, accessToken, new MatrixClient.MatrixCallback<JSONArray>() {
            @Override
            public void onSuccess(JSONArray devices) {
                TLRPC.TL_account_authorizations res = new TLRPC.TL_account_authorizations();
                res.authorization_ttl_days = 365;

                if (devices != null) {
                    for (int i = 0; i < devices.length(); i++) {
                        JSONObject dev = devices.optJSONObject(i);
                        if (dev == null) continue;

                        String devId = dev.optString("device_id", "");
                        String dispName = dev.optString("display_name", "");
                        String ip = dev.optString("last_seen_ip", "");
                        long lastSeenTs = dev.optLong("last_seen_ts", System.currentTimeMillis());

                        long hash = Math.abs((long) devId.hashCode());
                        deviceHashToIdMap.put(hash, devId);

                        TLRPC.TL_authorization auth = new TLRPC.TL_authorization();
                        auth.hash = hash;
                        auth.device_model = !TextUtils.isEmpty(dispName) ? dispName : "Matrix Session";
                        auth.platform = "Matrix";
                        auth.system_version = devId;
                        auth.app_name = "SGram";
                        auth.app_version = "v0.3.1";
                        auth.ip = ip;
                        auth.date_active = (int) (lastSeenTs / 1000);
                        auth.current = devId.equals(currentDeviceId);

                        res.authorizations.add(auth);
                    }
                }

                if (onComplete != null) {
                    onComplete.run(res, null);
                }
            }

            @Override
            public void onError(int code, String message) {
                if (onComplete != null) {
                    TLRPC.TL_account_authorizations res = new TLRPC.TL_account_authorizations();
                    onComplete.run(res, null);
                }
            }
        });
    }

    private void handleResetAuthorization(TLRPC.TL_account_resetAuthorization req, RequestDelegate onComplete) {
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);
        String devId = deviceHashToIdMap.get(req.hash);

        if (!TextUtils.isEmpty(devId)) {
            MatrixClient.getInstance().deleteDevice(homeserver, accessToken, devId, null);
        }

        if (onComplete != null) {
            onComplete.run(new TLRPC.TL_boolTrue(), null);
        }
    }

    private void handleResetAllAuthorizations(TLRPC.TL_auth_resetAuthorizations req, RequestDelegate onComplete) {
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);
        String currentDevId = MatrixConfig.getDeviceId(currentAccount);

        for (String devId : deviceHashToIdMap.values()) {
            if (!devId.equals(currentDevId)) {
                MatrixClient.getInstance().deleteDevice(homeserver, accessToken, devId, null);
            }
        }

        if (onComplete != null) {
            onComplete.run(new TLRPC.TL_boolTrue(), null);
        }
    }

    // ---------------- Native Polls (MSC3381) ----------------

    private void handleSendPoll(String matrixRoomId, TLRPC.TL_inputMediaPoll pollMedia, RequestDelegate onComplete) {
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        if (TextUtils.isEmpty(matrixRoomId) || pollMedia == null || pollMedia.poll == null) {
            if (onComplete != null) {
                TLRPC.TL_error err = new TLRPC.TL_error();
                err.code = 400;
                err.text = "Invalid poll or room";
                onComplete.run(null, err);
            }
            return;
        }

        String question = pollMedia.poll.question != null ? pollMedia.poll.question.text : "";
        List<String> options = new ArrayList<>();
        if (pollMedia.poll.answers != null) {
            for (TLRPC.PollAnswer a : pollMedia.poll.answers) {
                if (a != null && a.text != null) {
                    options.add(a.text.text);
                }
            }
        }
        boolean multipleChoice = pollMedia.poll.multiple_choice;
        String txnId = idMapper.getNextTxnId();

        MatrixClient.getInstance().sendPoll(homeserver, accessToken, matrixRoomId, question, options, multipleChoice, txnId, new MatrixClient.MatrixCallback<String>() {
            @Override
            public void onSuccess(String eventId) {
                TLRPC.TL_updates updates = new TLRPC.TL_updates();
                updates.date = (int) (System.currentTimeMillis() / 1000);
                if (onComplete != null) {
                    onComplete.run(updates, null);
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

    private void handleSendVote(TLRPC.TL_messages_sendVote req, RequestDelegate onComplete) {
        long peerDialogId = DialogObject.getPeerDialogId(req.peer);
        String matrixRoomId = idMapper.getMatrixRoomIdForDialog(peerDialogId);
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);
        String pollEventId = idMapper.getMatrixEventId(matrixRoomId, req.msg_id);

        if (TextUtils.isEmpty(matrixRoomId) || TextUtils.isEmpty(pollEventId)) {
            if (onComplete != null) {
                TLRPC.TL_updates updates = new TLRPC.TL_updates();
                onComplete.run(updates, null);
            }
            return;
        }

        List<String> answers = new ArrayList<>();
        if (req.options != null) {
            for (byte[] optBytes : req.options) {
                if (optBytes != null && optBytes.length > 0) {
                    answers.add(new String(optBytes, StandardCharsets.UTF_8));
                }
            }
        }

        String txnId = idMapper.getNextTxnId();
        MatrixClient.getInstance().sendPollVote(homeserver, accessToken, matrixRoomId, pollEventId, answers, txnId, new MatrixClient.MatrixCallback<String>() {
            @Override
            public void onSuccess(String eventId) {
                TLRPC.TL_updates updates = new TLRPC.TL_updates();
                updates.date = (int) (System.currentTimeMillis() / 1000);
                if (onComplete != null) {
                    onComplete.run(updates, null);
                }
            }

            @Override
            public void onError(int code, String message) {
                if (onComplete != null) {
                    TLRPC.TL_updates updates = new TLRPC.TL_updates();
                    onComplete.run(updates, null);
                }
            }
        });
    }
}
