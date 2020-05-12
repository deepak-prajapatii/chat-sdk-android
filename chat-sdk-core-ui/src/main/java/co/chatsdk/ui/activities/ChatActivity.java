/*
 * Created by Itzik Braun on 12/3/2015.
 * Copyright (c) 2015 deluge. All rights reserved.
 *
 * Last Modification at: 3/12/15 4:27 PM
 */

package co.chatsdk.ui.activities;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Debug;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.miguelcatalan.materialsearchview.MaterialSearchView;
import com.stfalcon.chatkit.messages.MessageInput;

import org.pmw.tinylog.Logger;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.concurrent.Callable;

import butterknife.BindView;
import co.chatsdk.ui.chat.model.ImageMessageHolder;
import co.chatsdk.ui.chat.model.MessageHolder;
import io.reactivex.Single;
import io.reactivex.SingleEmitter;
import io.reactivex.SingleOnSubscribe;
import io.reactivex.SingleSource;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.subjects.PublishSubject;
import sdk.chat.core.dao.Keys;
import sdk.chat.core.dao.Message;
import sdk.chat.core.dao.Thread;
import sdk.chat.core.dao.User;
import sdk.chat.core.events.EventType;
import sdk.chat.core.events.NetworkEvent;
import sdk.chat.core.handlers.TypingIndicatorHandler;
import sdk.chat.core.interfaces.ChatOption;
import sdk.chat.core.interfaces.ChatOptionsDelegate;
import sdk.chat.core.interfaces.ChatOptionsHandler;
import sdk.chat.core.interfaces.ThreadType;
import sdk.chat.core.session.ChatSDK;
import sdk.chat.core.utils.ActivityResultPushSubjectHolder;
import co.chatsdk.ui.R;
import co.chatsdk.ui.R2;
import co.chatsdk.ui.appbar.ChatActionBar;
import co.chatsdk.ui.audio.AudioBinder;
import co.chatsdk.ui.custom.Customiser;
import co.chatsdk.ui.icons.Icons;
import co.chatsdk.ui.interfaces.TextInputDelegate;
import co.chatsdk.ui.views.ChatView;
import co.chatsdk.ui.views.ReplyView;
import io.reactivex.Completable;
import sdk.chat.core.utils.StringChecker;
import sdk.guru.common.RX;
import sdk.guru.common.Optional;
import sdk.guru.common.RX;

public class ChatActivity extends BaseActivity implements TextInputDelegate, ChatOptionsDelegate, ChatView.Delegate {

    public static final int messageForwardActivityCode = 998;

    protected ChatOptionsHandler optionsHandler;

    // Should we remove the user from the public chat when we stop this activity?
    // If we are showing a temporary screen like the sticker text screen
    // this should be set to no
    protected boolean removeUserFromChatOnExit = true;

    protected static boolean enableTrace = false;

    protected Thread thread;

//    protected Bundle bundle;

    @BindView(R2.id.chatActionBar) protected ChatActionBar chatActionBar;
    @BindView(R2.id.chatView) protected ChatView chatView;
    @BindView(R2.id.divider) protected View divider;
    @BindView(R2.id.replyView) protected ReplyView replyView;
    @BindView(R2.id.input) protected MessageInput input;
    @BindView(R2.id.viewContainer) protected CoordinatorLayout viewContainer;
    @BindView(R2.id.searchView) protected MaterialSearchView searchView;
    @BindView(R2.id.root) protected FrameLayout root;
    @BindView(R2.id.messageInputLinearLayout) protected LinearLayout messageInputLinearLayout;

    protected Single<Optional<Thread>> threadSingle = null;

    AudioBinder audioBinder = null;

    protected @LayoutRes
    int getLayout() {
        return R.layout.activity_chat;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getThread(savedInstanceState).doOnSuccess(threadOptional -> {
            if (!threadOptional.isEmpty()) {
                initViews();
            } else {
                finish();
            }
        }).ignoreElement().subscribe(this);
    }

    public void updateOptionsButton() {
        input.findViewById(R.id.attachmentButton).setVisibility(chatView.getSelectedMessages().isEmpty() ? View.VISIBLE : View.GONE);
        input.findViewById(R.id.attachmentButtonSpace).setVisibility(chatView.getSelectedMessages().isEmpty() ? View.VISIBLE : View.GONE);
    }

    public void hideTextInput() {
        input.setVisibility(View.GONE);
        divider.setVisibility(View.GONE);
        updateChatViewMargins();
    }

    public void showTextInput() {
        input.setVisibility(View.VISIBLE);
        divider.setVisibility(View.VISIBLE);
        updateChatViewMargins();
    }

    public void hideReplyView() {
        audioBinder.hideReplyView();
        chatView.clearSelection();
        replyView.hide();
        updateOptionsButton();

        // We need this otherwise the margin isn't updated when the view is gone
//        CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) chatView.getLayoutParams();
//        params.setMargins(params.leftMargin, params.topMargin, params.rightMargin, messageInputLinearLayout.getHeight() - replyView.getHeight());
//        chatView.setLayoutParams(params);

        updateChatViewMargins();

    }


    public void updateChatViewMargins() {

        int bottomMargin = 0;
        if (replyView.isVisible()) {
            bottomMargin += replyView.getHeight();
        }
        if (input.getVisibility() != View.GONE) {
            bottomMargin += input.getHeight() + divider.getHeight();
        }

        CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) chatView.getLayoutParams();
        params.setMargins(params.leftMargin, params.topMargin, params.rightMargin, bottomMargin);
        chatView.setLayoutParams(params);

    }

    public void showReplyView(String title, String imageURL, String text) {
        updateOptionsButton();
        audioBinder.showReplyView();
        replyView.show(title, imageURL, text);

        // We need this otherwise the margin isn't updated when the view is gone
//        CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) chatView.getLayoutParams();
//        params.setMargins(params.leftMargin, params.topMargin, params.rightMargin, messageInputLinearLayout.getHeight() + replyView.getHeight());
//        chatView.setLayoutParams(params);

        updateChatViewMargins();

    }

    @Override
    protected Bitmap getTaskDescriptionBitmap() {
        return super.getTaskDescriptionBitmap();
    }

    protected void initViews() {
        super.initViews();

        chatView.setDelegate(this);

        chatActionBar.onSearchClicked(v -> {
            searchView.showSearch();
        });

        searchView.setOnQueryTextListener(new MaterialSearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                chatView.filter(query);
                chatActionBar.hideSearchIcon();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                chatView.filter(newText);
                chatActionBar.hideSearchIcon();
                return false;
            }
        });

        searchView.setOnSearchViewListener(new MaterialSearchView.SearchViewListener() {
            @Override
            public void onSearchViewShown() {

            }

            @Override
            public void onSearchViewClosed() {
                chatView.clearFilter();
                chatActionBar.showSearchIcon();
            }
        });

        chatView.initViews();

        chatView.enableSelectionMode(count -> {
            invalidateOptionsMenu();
            updateOptionsButton();
        });

        if (!ChatSDK.thread().hasVoice(thread, ChatSDK.currentUser())) {
            hideTextInput();
        }

        if (ChatSDK.audioMessage() != null) {
            audioBinder = new AudioBinder(this, this, input);
        } else {
            input.setInputListener(input -> {
                sendMessage(String.valueOf(input));
                return true;
            });
        }

        input.setTypingListener(new MessageInput.TypingListener() {
            @Override
            public void onStartTyping() {
                startTyping();
            }

            @Override
            public void onStopTyping() {
                stopTyping();
            }
        });

        input.setAttachmentsListener(this::showOptions);

        replyView.setOnCancelListener(v -> hideReplyView());

        // Action bar
        chatActionBar.setOnClickListener(v -> openThreadDetailsActivity());
        setSupportActionBar(chatActionBar.getToolbar());
        chatActionBar.reload(thread);


        setChatState(TypingIndicatorHandler.State.active);

        if (enableTrace) {
            Debug.startMethodTracing("chat");
        }

        dm.add(ChatSDK.events().sourceOnMain()
                .filter(NetworkEvent.filterType(EventType.ThreadDetailsUpdated, EventType.ThreadUsersUpdated))
                .filter(NetworkEvent.filterThreadEntityID(thread.getEntityID()))
                .subscribe(networkEvent -> chatActionBar.reload(thread)));

        dm.add(ChatSDK.events().sourceOnMain()
                .filter(NetworkEvent.filterType(EventType.UserMetaUpdated, EventType.UserPresenceUpdated))
                .filter(NetworkEvent.filterThreadEntityID(thread.getEntityID()))
                .filter(networkEvent -> thread.containsUser(networkEvent.getUser()))
                .subscribe(networkEvent -> {
                    reloadData();
                    chatActionBar.reload(thread);
                }));

        dm.add(ChatSDK.events().sourceOnMain()
                .filter(NetworkEvent.filterType(EventType.TypingStateUpdated))
                .filter(NetworkEvent.filterThreadEntityID(thread.getEntityID()))
                .subscribe(networkEvent -> {
                    String typingText = networkEvent.getText();
                    if (typingText != null) {
                        typingText += getString(R.string.typing);
                    }
                    Logger.debug(typingText);
                    chatActionBar.setSubtitleText(thread, typingText);
                }));

        dm.add(ChatSDK.events().sourceOnMain()
                .filter(NetworkEvent.filterType(EventType.ThreadUserRoleUpdated))
                .filter(NetworkEvent.filterThreadEntityID(thread.getEntityID()))
                .filter(NetworkEvent.filterUserEntityID(ChatSDK.currentUserID()))
                .subscribe(networkEvent -> {
                    if (ChatSDK.thread().hasVoice(thread, networkEvent.getUser())) {
                        showTextInput();
                    } else {
                        hideTextInput();
                    }
                }));

        thread.markReadAsync().subscribe();

        invalidateOptionsMenu();
    }

    /**
     * Send text text
     *
     * @param text to send.
     */
    public void sendMessage(String text) {

        // Clear the draft text
        thread.setDraft(null);

        if (text == null || text.isEmpty() || text.replace(" ", "").isEmpty()) {
            return;
        }

        if (replyView.isVisible()) {
            MessageHolder holder = chatView.getSelectedMessages().get(0);
            handleMessageSend(ChatSDK.thread().replyToMessage(thread, holder.getMessage(), text));
            hideReplyView();
        } else {
            handleMessageSend(ChatSDK.thread().sendMessageWithText(text.trim(), thread));
        }

    }

    protected void handleMessageSend(Completable completable) {
        completable.observeOn(RX.main()).subscribe(this);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        // Save the thread ID
        if (thread != null) {
            outState.putString(Keys.IntentKeyThreadEntityID, thread.getEntityID());
        }

    }

    protected void reloadData() {
        chatView.notifyDataSetChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();

        removeUserFromChatOnExit = !ChatSDK.config().publicChatAutoSubscriptionEnabled;

        getThread(null).doOnSuccess(threadOptional -> {
            if (thread != null && thread.typeIs(ThreadType.Public)) {
                User currentUser = ChatSDK.currentUser();
                ChatSDK.thread().addUsersToThread(thread, currentUser).subscribe();
            }

            chatActionBar.setSubtitleText(thread, null);

            // Show a local notification if the text is from a different thread
            ChatSDK.ui().setLocalNotificationHandler(thread -> !thread.getEntityID().equals(this.thread.getEntityID()));

            if (audioBinder != null) {
                audioBinder.updateRecordMode();
            }

            if (!StringChecker.isNullOrEmpty(thread.getDraft())) {
                input.getInputEditText().setText(thread.getDraft());
            }

        }).ignoreElement().subscribe();

    }

    @Override
    protected void onPause() {
        super.onPause();
        hideKeyboard();

        if (!StringChecker.isNullOrEmpty(input.getInputEditText().getText())) {
            thread.setDraft(input.getInputEditText().getText().toString());
        } else {
            thread.setDraft(null);
        }

    }

    /**
     * Sending a broadcast that the chat was closed, Only if there were new messageHolders on this chat.
     * This is used for example to update the thread list that messageHolders has been read.
     */
    @Override
    protected void onStop() {
        super.onStop();

        becomeInactive();

        if (thread != null && thread.typeIs(ThreadType.Public) && (removeUserFromChatOnExit || thread.isMuted())) {
            // Don't add this to activity disposable map because otherwise it can be cancelled before completion
            ChatSDK.events().disposeOnLogout(ChatSDK.thread()
                    .removeUsersFromThread(thread, ChatSDK.currentUser())
                    .observeOn(RX.main()).subscribe());
        }

    }

    /**
     * Not used, There is a piece of code here that could be used to clean all images that was loaded for this chat from cache.
     */
    @Override
    protected void onDestroy() {
        if (enableTrace) {
            Debug.stopMethodTracing();
        }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        thread = null;
        getThread(intent.getExtras()).doOnSuccess(threadOptional -> {
            if (!threadOptional.isEmpty()) {
                clear();
                chatView.onLoadMore(0, 0);
                chatActionBar.reload(thread);
            } else {
                finish();
            }
        }).ignoreElement().subscribe(this);
    }

    public void clear() {
        chatView.clear();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (thread != null) {
            if (!chatView.getSelectedMessages().isEmpty()) {

                chatActionBar.hideSearchIcon();

                getMenuInflater().inflate(R.menu.activity_chat_actions_menu, menu);

                menu.findItem(R.id.action_copy).setIcon(Icons.get(Icons.choose().copy, Icons.shared().actionBarIconColor));
                menu.findItem(R.id.action_delete).setIcon(Icons.get(Icons.choose().delete, Icons.shared().actionBarIconColor));
                menu.findItem(R.id.action_forward).setIcon(Icons.get(Icons.choose().forward, Icons.shared().actionBarIconColor));
                menu.findItem(R.id.action_reply).setIcon(Icons.get(Icons.choose().reply, Icons.shared().actionBarIconColor));

                if (chatView.getSelectedMessages().size() != 1) {
                    menu.removeItem(R.id.action_reply);
                }

                if (!ChatSDK.thread().hasVoice(thread, ChatSDK.currentUser())) {
                    menu.removeItem(R.id.action_reply);
                    menu.removeItem(R.id.action_delete);
                    menu.removeItem(R.id.action_forward);
                }

                // Check that the messages could be deleted
                boolean canBeDeleted = true;
                for (MessageHolder holder: chatView.getSelectedMessages()) {
                    if (!ChatSDK.thread().canDeleteMessage(holder.getMessage())) {
                        canBeDeleted = false;
                    }
                }
                if (!canBeDeleted) {
                    menu.removeItem(R.id.action_delete);
                }

                chatActionBar.hideText();
            } else {

                chatActionBar.showSearchIcon();

                if (ChatSDK.thread().canAddUsersToThread(thread)) {
                    getMenuInflater().inflate(R.menu.add_menu, menu);
                    menu.findItem(R.id.action_add).setIcon(Icons.get(Icons.choose().add, Icons.shared().actionBarIconColor));
                }

                chatActionBar.showText();
            }
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        /* Cant use switch in the library*/
        int id = item.getItemId();
        if (id == R.id.action_delete) {
            List<MessageHolder> holders = chatView.getSelectedMessages();
            ChatSDK.thread().deleteMessages(MessageHolder.toMessages(holders)).subscribe(this);
            clearSelection();
        }
        if (id == R.id.action_copy) {
            chatView.copySelectedMessagesText(this, holder -> {
                DateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                return dateFormatter.format(holder.getCreatedAt()) + ", " + holder.getUser().getName() + ": " + holder.getText();
            }, false);
            showToast(R.string.copied_to_clipboard);
        }
        if (id == R.id.action_forward) {

            List<MessageHolder> holders = chatView.getSelectedMessages();

            dm.put(messageForwardActivityCode, ActivityResultPushSubjectHolder.shared().subscribe(activityResult -> {
                if (activityResult.requestCode == messageForwardActivityCode) {
                    if (activityResult.resultCode == Activity.RESULT_OK) {
                        showToast(R.string.success);
                    } else {
                        if (activityResult.data != null) {
                            String errorMessage = activityResult.data.getStringExtra(Keys.IntentKeyErrorMessage);
                            showToast(errorMessage);
                        }
                    }
                    dm.dispose(messageForwardActivityCode);
                }
            }));

            // We don't want to remove the user if we load another activity
            // Like the sticker activity
            removeUserFromChatOnExit = false;

            ChatSDK.ui().startForwardMessageActivityForResult(this, thread, MessageHolder.toMessages(holders), messageForwardActivityCode);
            clearSelection();
        }

        if (id == R.id.action_reply) {
            MessageHolder holder = chatView.getSelectedMessages().get(0);
            String imageURL = null;
            if (holder instanceof ImageMessageHolder) {
                imageURL = ((ImageMessageHolder) holder).getImageUrl();
            }
            showReplyView(holder.getUser().getName(), imageURL, holder.getText());
            input.requestFocus();
            showKeyboard();
        }

        if (id == R.id.action_add) {

            // We don't want to remove the user if we load another activity
            // Like the sticker activity
            removeUserFromChatOnExit = false;

            ChatSDK.ui().startAddUsersToThreadActivity(this, thread.getEntityID());
        }

        return super.onOptionsItemSelected(item);
    }

    public void clearSelection() {
        chatView.clearSelection();
        updateOptionsButton();
    }

    /**
     * Open the thread details context, Admin user can change thread name an messageImageView there.
     */
    protected void openThreadDetailsActivity() {

        // We don't want to remove the user if we load another activity
        // Like the sticker activity
        removeUserFromChatOnExit = false;

        ChatSDK.ui().startThreadDetailsActivity(this, thread.getEntityID());
    }

    protected Single<Optional<Thread>> getThread(final Bundle inputBundle) {
        return Single.defer(() -> {
            if (thread != null) {
                return Single.just(new Optional<>(thread));
            } if (threadSingle == null) {
                threadSingle = Single.create((SingleOnSubscribe<Optional<Thread>>) emitter -> {
                    Bundle bundle = inputBundle;
                    if (bundle == null) {
                        bundle = getIntent().getExtras();
                    }

                    if (bundle.containsKey(Keys.IntentKeyThreadEntityID)) {
                        String threadEntityID = bundle.getString(Keys.IntentKeyThreadEntityID);
                        if (threadEntityID != null && thread == null) {
                            thread = ChatSDK.db().fetchThreadWithEntityID(threadEntityID);
                            emitter.onSuccess(new Optional<>(thread));
                            return;
                        }
                    }
                    emitter.onSuccess(new Optional<>());
                }).subscribeOn(RX.db()).observeOn(RX.main());
            }
            return threadSingle;
        });
    }

    @Override
    public void sendAudio(final File file, String mimeType, long duration) {
        if (ChatSDK.audioMessage() != null) {
            handleMessageSend(ChatSDK.audioMessage().sendMessage(this, file, mimeType, duration, thread));
        }
    }

    public void startTyping() {
        setChatState(TypingIndicatorHandler.State.composing);
    }

    public void becomeInactive() {
        setChatState(TypingIndicatorHandler.State.inactive);
    }

    public void stopTyping() {
        setChatState(TypingIndicatorHandler.State.active);
    }

    protected void setChatState(TypingIndicatorHandler.State state) {
        if (ChatSDK.typingIndicator() != null) {
            ChatSDK.typingIndicator().setChatState(state, thread)
                    .observeOn(RX.main())
                    .subscribe(this);
        }
    }

    /**
     * Show the option popup when the add_menu key is pressed.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MENU:
                showOptions();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public void showOptions() {
        // We don't want to remove the user if we load another activity
        // Like the sticker activity
        removeUserFromChatOnExit = false;

        optionsHandler = ChatSDK.ui().getChatOptionsHandler(this);
        optionsHandler.show(this);
    }

    @Override
    public void executeChatOption(ChatOption option) {
        handleMessageSend(option.execute(this, thread));
    }

    @Override
    public Thread getThread() {
        return thread;
    }

    @Override
    public void onClick(Message message) {
        Customiser.shared().onClick(this, root, message);
    }

    @Override
    public void onLongClick(Message message) {
        Customiser.shared().onLongClick(this, root, message);
    }

    @Override
    public void onBackPressed() {
        // Do this so that even if we were editing the thread, we always go back to the
        // main activity

        ChatSDK.ui().startMainActivity(this);
    }


}
