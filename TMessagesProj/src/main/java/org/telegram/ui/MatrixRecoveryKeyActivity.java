package org.telegram.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.matrix.MatrixClient;
import org.telegram.messenger.matrix.MatrixConfig;
import org.telegram.messenger.matrix.MatrixKeyBackupManager;
import org.telegram.messenger.matrix.MatrixRecoveryKeyHelper;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

public class MatrixRecoveryKeyActivity extends BaseFragment {

    private TextSettingsCell statusCell;
    private TextSettingsCell countCell;
    private TextSettingsCell homeserverCell;
    private TextSettingsCell versionCell;

    private TextCell restoreCell;
    private TextCell createCell;
    private TextCell backupNowCell;
    private TextCell viewStoredKeyCell;
    private TextCell deleteCell;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        loadStatus();
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Matrix Recovery Key");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        frameLayout.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // 1. Header Banner
        LinearLayout headerBanner = new LinearLayout(context);
        headerBanner.setOrientation(LinearLayout.VERTICAL);
        headerBanner.setGravity(Gravity.CENTER_HORIZONTAL);
        headerBanner.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        headerBanner.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24));

        ImageView iconView = new ImageView(context);
        iconView.setImageResource(R.drawable.msg2_secret);
        iconView.setColorFilter(Theme.getColor(Theme.key_chats_actionBackground));
        headerBanner.addView(iconView, LayoutHelper.createLinear(64, 64, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));

        TextView titleView = new TextView(context);
        titleView.setText("Matrix End-to-End Key Backup");
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        titleView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        headerBanner.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 8));

        TextView descView = new TextView(context);
        descView.setText("Your chat encryption keys are securely backed up. If you log in on a new device or reinstall the app, your Recovery Key restores access to historical encrypted messages.");
        descView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        descView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        descView.setGravity(Gravity.CENTER_HORIZONTAL);
        descView.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        headerBanner.addView(descView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        container.addView(headerBanner, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        container.addView(new ShadowSectionCell(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // 2. Status Section
        HeaderCell statusHeader = new HeaderCell(context);
        statusHeader.setText("Backup Status");
        statusHeader.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        container.addView(statusHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        statusCell = new TextSettingsCell(context);
        statusCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        container.addView(statusCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        versionCell = new TextSettingsCell(context);
        versionCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        container.addView(versionCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        countCell = new TextSettingsCell(context);
        countCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        container.addView(countCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        homeserverCell = new TextSettingsCell(context);
        homeserverCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        container.addView(homeserverCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextInfoPrivacyCell statusInfo = new TextInfoPrivacyCell(context);
        statusInfo.setText("Server backups are encrypted using Curve25519 asymmetric cryptography. The homeserver cannot view or decrypt your room keys.");
        container.addView(statusInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // 3. Actions Section
        HeaderCell actionsHeader = new HeaderCell(context);
        actionsHeader.setText("Key Backup Actions");
        actionsHeader.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        container.addView(actionsHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        restoreCell = new TextCell(context);
        restoreCell.setTextAndIcon("Restore Keys with Recovery Key", R.drawable.msg2_secret, true);
        restoreCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        restoreCell.setOnClickListener(v -> promptRestoreWithKey());
        container.addView(restoreCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        createCell = new TextCell(context);
        createCell.setTextAndIcon("Create New Backup & Generate Key", R.drawable.msg2_permissions, true);
        createCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        createCell.setOnClickListener(v -> confirmCreateNewBackup());
        container.addView(createCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        backupNowCell = new TextCell(context);
        backupNowCell.setTextAndIcon("Backup Local Keys to Server Now", R.drawable.msg_retry, true);
        backupNowCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        backupNowCell.setOnClickListener(v -> backupLocalKeysNow());
        container.addView(backupNowCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        viewStoredKeyCell = new TextCell(context);
        viewStoredKeyCell.setTextAndIcon("View Current Recovery Key", R.drawable.msg_copy, true);
        viewStoredKeyCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        viewStoredKeyCell.setOnClickListener(v -> showStoredRecoveryKey());
        container.addView(viewStoredKeyCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        deleteCell = new TextCell(context);
        deleteCell.setTextAndIcon("Delete / Reset Server Key Backup", R.drawable.msg_delete, false);
        deleteCell.setColors(Theme.key_text_RedRegular, Theme.key_text_RedRegular);
        deleteCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        deleteCell.setOnClickListener(v -> confirmDeleteBackup());
        container.addView(deleteCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextInfoPrivacyCell actionsInfo = new TextInfoPrivacyCell(context);
        actionsInfo.setText("Keep your Recovery Key safe in a password manager. If you lose your Recovery Key and log out, previous encrypted messages will be permanently unrecoverable.");
        container.addView(actionsInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        updateUI();
        return fragmentView;
    }

    private void updateUI() {
        if (statusCell == null) return;

        MatrixKeyBackupManager mgr = MatrixKeyBackupManager.getInstance(currentAccount);
        boolean active = mgr.isBackupActive();

        statusCell.setTextAndValue("Backup Status", active ? "Active & Encrypted" : "Not Configured", true);
        versionCell.setTextAndValue("Backup Version", active ? mgr.getBackupVersion() : "—", true);
        countCell.setTextAndValue("Backed Up Keys", active ? (mgr.getBackupKeysCount() + " keys") : "0 keys", true);
        homeserverCell.setTextAndValue("Homeserver", MatrixConfig.getHomeserver(currentAccount), false);

        String storedKey = mgr.getStoredRecoveryKey();
        viewStoredKeyCell.setVisibility(!TextUtils.isEmpty(storedKey) ? View.VISIBLE : View.GONE);
        backupNowCell.setVisibility(active ? View.VISIBLE : View.GONE);
        deleteCell.setVisibility(active ? View.VISIBLE : View.GONE);
    }

    private void loadStatus() {
        MatrixKeyBackupManager.getInstance(currentAccount).checkBackupStatus(new MatrixClient.MatrixCallback<MatrixKeyBackupManager.BackupStatus>() {
            @Override
            public void onSuccess(MatrixKeyBackupManager.BackupStatus status) {
                updateUI();
            }

            @Override
            public void onError(int code, String message) {
                updateUI();
            }
        });
    }

    // ---------------- Dialogs & Actions ----------------

    private void promptRestoreWithKey() {
        if (getParentActivity() == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Restore Keys with Recovery Key");
        builder.setMessage("Enter your 48-character Matrix Recovery Key (e.g. Es01 ...) or 32-byte Base64 key to restore historical messages:");

        final EditText input = new EditText(getParentActivity());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setHint("Es01 xxxx xxxx xxxx ...");
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));

        FrameLayout container = new FrameLayout(getParentActivity());
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        builder.setView(container);

        builder.setPositiveButton("Restore", (dialog, which) -> {
            String key = input.getText().toString().trim();
            if (TextUtils.isEmpty(key)) {
                return;
            }
            executeRestore(key);
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void executeRestore(String key) {
        if (getParentActivity() == null) return;

        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setMessage("Decrypting and importing keys...");
        progress.setCanceledOnTouchOutside(false);
        showDialog(progress);

        MatrixKeyBackupManager.getInstance(currentAccount).restoreKeysWithRecoveryKey(key, new MatrixClient.MatrixCallback<Integer>() {
            @Override
            public void onSuccess(Integer count) {
                progress.dismiss();
                updateUI();
                if (getParentActivity() != null) {
                    new AlertDialog.Builder(getParentActivity())
                            .setTitle("Keys Restored Successfully")
                            .setMessage("Successfully restored " + count + " room keys! Historical messages can now be decrypted.")
                            .setPositiveButton("OK", null)
                            .show();
                }
            }

            @Override
            public void onError(int code, String message) {
                progress.dismiss();
                if (getParentActivity() != null) {
                    new AlertDialog.Builder(getParentActivity())
                            .setTitle("Key Restore Failed")
                            .setMessage(message != null ? message : "Unable to restore room keys with this Recovery Key.")
                            .setPositiveButton("OK", null)
                            .show();
                }
            }
        });
    }

    private void confirmCreateNewBackup() {
        if (getParentActivity() == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Create New Key Backup");
        builder.setMessage("This will generate a new Matrix Recovery Key and register an encrypted backup version on your homeserver.\n\nMake sure to save the generated Recovery Key safely.");
        builder.setPositiveButton("Generate & Setup", (dialog, which) -> executeCreateNewBackup());
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void executeCreateNewBackup() {
        if (getParentActivity() == null) return;

        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setMessage("Setting up key backup...");
        progress.setCanceledOnTouchOutside(false);
        showDialog(progress);

        MatrixKeyBackupManager.getInstance(currentAccount).createKeyBackup(new MatrixClient.MatrixCallback<String>() {
            @Override
            public void onSuccess(String recoveryKey) {
                progress.dismiss();
                updateUI();
                showGeneratedKeyDialog(recoveryKey);
            }

            @Override
            public void onError(int code, String message) {
                progress.dismiss();
                if (getParentActivity() != null) {
                    new AlertDialog.Builder(getParentActivity())
                            .setTitle("Setup Failed")
                            .setMessage(message != null ? message : "Unable to create key backup on server.")
                            .setPositiveButton("OK", null)
                            .show();
                }
            }
        });
    }

    private void showGeneratedKeyDialog(final String recoveryKey) {
        if (getParentActivity() == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Your Recovery Key");

        LinearLayout layout = new LinearLayout(getParentActivity());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(12), AndroidUtilities.dp(24), AndroidUtilities.dp(12));

        TextView warning = new TextView(getParentActivity());
        warning.setText("Save this Recovery Key in a safe place. You will need it to restore your chat history if you log in from a new device:");
        warning.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        warning.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        layout.addView(warning, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        TextView keyBox = new TextView(getParentActivity());
        keyBox.setText(recoveryKey);
        keyBox.setTypeface(Typeface.MONOSPACE);
        keyBox.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        keyBox.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        keyBox.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        keyBox.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        keyBox.setTextIsSelectable(true);
        layout.addView(keyBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        builder.setView(layout);
        builder.setPositiveButton("Copy Key", (dialog, which) -> {
            AndroidUtilities.addToClipboard(recoveryKey);
            Toast.makeText(getParentActivity(), "Recovery Key copied to clipboard", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Done", null);
        showDialog(builder.create());
    }

    private void showStoredRecoveryKey() {
        String key = MatrixKeyBackupManager.getInstance(currentAccount).getStoredRecoveryKey();
        if (!TextUtils.isEmpty(key)) {
            showGeneratedKeyDialog(key);
        } else {
            Toast.makeText(getParentActivity(), "No stored recovery key available on this device", Toast.LENGTH_SHORT).show();
        }
    }

    private void backupLocalKeysNow() {
        if (getParentActivity() == null) return;

        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setMessage("Uploading keys to server backup...");
        progress.setCanceledOnTouchOutside(false);
        showDialog(progress);

        MatrixKeyBackupManager.getInstance(currentAccount).backupLocalKeys(new MatrixClient.MatrixCallback<Integer>() {
            @Override
            public void onSuccess(Integer count) {
                progress.dismiss();
                updateUI();
                Toast.makeText(getParentActivity(), "Successfully backed up " + count + " room keys", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(int code, String message) {
                progress.dismiss();
                Toast.makeText(getParentActivity(), "Backup failed: " + message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void confirmDeleteBackup() {
        if (getParentActivity() == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Delete Key Backup");
        builder.setMessage("Are you sure you want to delete your key backup from the homeserver? You will need to create a new backup.");
        builder.setPositiveButton("Delete", (dialog, which) -> {
            MatrixKeyBackupManager.getInstance(currentAccount).deleteKeyBackup(new MatrixClient.MatrixCallback<Boolean>() {
                @Override
                public void onSuccess(Boolean result) {
                    updateUI();
                    Toast.makeText(getParentActivity(), "Key backup deleted from server", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int code, String message) {
                    updateUI();
                }
            });
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }
}
