package org.telegram.ui.Components;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.matrix.MatrixClient;
import org.telegram.messenger.matrix.MatrixConfig;
import org.telegram.messenger.matrix.MatrixController;
import org.telegram.messenger.matrix.MatrixIdMapper;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LoginActivity;

public class MatrixLoginView extends SlideView {

    private final LoginActivity loginActivity;
    private final int currentAccount;

    private TextView titleView;
    private TextView subtitleView;

    private OutlineTextContainerView homeserverOutlineView;
    private EditTextBoldCursor homeserverField;

    private OutlineTextContainerView usernameOutlineView;
    private EditTextBoldCursor usernameField;

    private OutlineTextContainerView passwordOutlineView;
    private EditTextBoldCursor passwordField;

    private boolean isLoggingIn = false;

    public MatrixLoginView(Context context, LoginActivity activity, int account) {
        super(context);
        this.loginActivity = activity;
        this.currentAccount = account;

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);

        // Title
        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setText("Matrix Login");
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setGravity(Gravity.CENTER);
        addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 16, 24, 0));

        // Subtitle
        subtitleView = new TextView(context);
        subtitleView.setText("Enter your Matrix Homeserver and credentials to log in.");
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
        subtitleView.setGravity(Gravity.CENTER);
        addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 8, 24, 20));

        // 1. Homeserver Field
        homeserverOutlineView = new OutlineTextContainerView(context);
        homeserverOutlineView.setText("Homeserver URL");
        homeserverField = new EditTextBoldCursor(context);
        homeserverField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        homeserverField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        homeserverField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        homeserverField.setText(MatrixConfig.DEFAULT_HOMESERVER);
        homeserverField.setMaxLines(1);
        homeserverField.setSingleLine(true);
        homeserverField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        homeserverField.setBackground(null);
        homeserverField.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        homeserverField.setOnFocusChangeListener((v, hasFocus) -> homeserverOutlineView.animateSelection(hasFocus ? 1f : 0f));
        homeserverOutlineView.attachEditText(homeserverField);
        homeserverOutlineView.addView(homeserverField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        addView(homeserverOutlineView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 58, 16, 8, 16, 8));

        // 2. Username Field
        usernameOutlineView = new OutlineTextContainerView(context);
        usernameOutlineView.setText("Username or Matrix ID");
        usernameField = new EditTextBoldCursor(context);
        usernameField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        usernameField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        usernameField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        usernameField.setHint("@user:matrix.org or username");
        usernameField.setMaxLines(1);
        usernameField.setSingleLine(true);
        usernameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        usernameField.setBackground(null);
        usernameField.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        usernameField.setOnFocusChangeListener((v, hasFocus) -> usernameOutlineView.animateSelection(hasFocus ? 1f : 0f));
        usernameOutlineView.attachEditText(usernameField);
        usernameOutlineView.addView(usernameField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        addView(usernameOutlineView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 58, 16, 8, 16, 8));

        // 3. Password Field
        passwordOutlineView = new OutlineTextContainerView(context);
        passwordOutlineView.setText("Password");
        passwordField = new EditTextBoldCursor(context);
        passwordField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        passwordField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        passwordField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        passwordField.setMaxLines(1);
        passwordField.setSingleLine(true);
        passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordField.setBackground(null);
        passwordField.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        passwordField.setOnFocusChangeListener((v, hasFocus) -> passwordOutlineView.animateSelection(hasFocus ? 1f : 0f));
        passwordField.setOnEditorActionListener((textView, actionId, keyEvent) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                onNextPressed(null);
                return true;
            }
            return false;
        });
        passwordOutlineView.attachEditText(passwordField);
        passwordOutlineView.addView(passwordField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        addView(passwordOutlineView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 58, 16, 8, 16, 16));
    }

    @Override
    public String getHeaderName() {
        return "Matrix Login";
    }

    @Override
    public void onNextPressed(String code) {
        if (isLoggingIn) {
            return;
        }

        String homeserver = homeserverField.getText() != null ? homeserverField.getText().toString().trim() : "";
        String username = usernameField.getText() != null ? usernameField.getText().toString().trim() : "";
        String password = passwordField.getText() != null ? passwordField.getText().toString() : "";

        if (TextUtils.isEmpty(homeserver)) {
            AndroidUtilities.shakeView(homeserverOutlineView);
            return;
        }
        if (TextUtils.isEmpty(username)) {
            AndroidUtilities.shakeView(usernameOutlineView);
            return;
        }
        if (TextUtils.isEmpty(password)) {
            AndroidUtilities.shakeView(passwordOutlineView);
            return;
        }

        isLoggingIn = true;
        if (loginActivity != null) {
            loginActivity.needShowProgress();
        }

        MatrixClient.getInstance().login(homeserver, username, password, new MatrixClient.MatrixCallback<MatrixClient.LoginResult>() {
            @Override
            public void onSuccess(MatrixClient.LoginResult result) {
                isLoggingIn = false;
                if (loginActivity != null) {
                    loginActivity.needHideProgress();
                }

                // 1. Save session in MatrixConfig
                MatrixConfig.saveSession(currentAccount, homeserver, result.userId, result.accessToken, result.deviceId, result.displayName);

                // 2. Map Matrix user to numeric Telegram ID
                long numericUserId = MatrixIdMapper.getInstance(currentAccount).getNumericUserId(result.userId);

                // 3. Create TLRPC User
                TLRPC.TL_user currentUser = new TLRPC.TL_user();
                currentUser.id = numericUserId;
                currentUser.first_name = !TextUtils.isEmpty(result.displayName) ? result.displayName : result.userId;
                currentUser.username = result.userId.replaceFirst("^@", "").replace(':', '_');
                currentUser.self = true;
                currentUser.status = new TLRPC.TL_userStatusOnline();

                // 4. Save UserConfig and start MatrixController
                UserConfig.getInstance(currentAccount).setCurrentUser(currentUser);
                UserConfig.getInstance(currentAccount).saveConfig(true);
                MatrixController.getInstance(currentAccount).start();

                // 5. Notify LoginActivity to complete auth and switch to DialogsActivity
                if (loginActivity != null) {
                    loginActivity.onMatrixAuthSuccess(currentUser);
                }
            }

            @Override
            public void onError(int code, String message) {
                isLoggingIn = false;
                if (loginActivity != null) {
                    loginActivity.needHideProgress();
                }
                if (getContext() != null) {
                    new AlertDialog.Builder(getContext())
                            .setTitle("Matrix Login Error")
                            .setMessage(message != null ? message : "Unable to connect to homeserver.")
                            .setPositiveButton("OK", null)
                            .show();
                }
            }
        });
    }
}
