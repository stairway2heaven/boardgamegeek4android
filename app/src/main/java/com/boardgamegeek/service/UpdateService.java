package com.boardgamegeek.service;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationCompat.Builder;
import android.text.TextUtils;

import com.boardgamegeek.R;
import com.boardgamegeek.events.UpdateCompleteEvent;
import com.boardgamegeek.events.UpdateErrorEvent;
import com.boardgamegeek.events.UpdateEvent;
import com.boardgamegeek.provider.BggContract;
import com.boardgamegeek.util.NetworkUtils;
import com.boardgamegeek.util.NotificationUtils;
import com.boardgamegeek.util.PreferencesUtils;

import org.greenrobot.eventbus.EventBus;

import timber.log.Timber;

public class UpdateService extends IntentService {
	private static final String KEY_SYNC_TYPE = "KEY_SYNC_TYPE";
	private static final String KEY_SYNC_ID = "KEY_SYNC_ID";
	private static final String KEY_SYNC_KEY = "KEY_SYNC_KEY";

	public static final int SYNC_TYPE_GAME = 1;
	public static final int SYNC_TYPE_GAME_PLAYS = 2;
	public static final int SYNC_TYPE_GAME_COLLECTION = 3;
	public static final int SYNC_TYPE_BUDDY = 4;
	public static final int SYNC_TYPE_BUDDY_SELF = 5;
	public static final int SYNC_TYPE_DESIGNER = 10;
	public static final int SYNC_TYPE_ARTIST = 11;
	public static final int SYNC_TYPE_PUBLISHER = 12;
	public static final int SYNC_TYPE_PLAYS_DATE = 20;
	private static final int SYNC_TYPE_UNKNOWN = 0;

	public static void start(@NonNull Context context, int type, int id) {
		context.startService(new Intent(Intent.ACTION_SYNC, null, context, UpdateService.class)
			.putExtra(KEY_SYNC_TYPE, type)
			.putExtra(KEY_SYNC_ID, id));
	}

	public static void start(@NonNull Context context, int type, String key) {
		context.startService(new Intent(Intent.ACTION_SYNC, null, context, UpdateService.class)
			.putExtra(KEY_SYNC_TYPE, type)
			.putExtra(KEY_SYNC_KEY, key));
	}

	public UpdateService() {
		super("BGG-UpdateService");
	}

	@Override
	protected void onHandleIntent(@NonNull Intent intent) {
		Timber.d("onHandleIntent(intent=" + intent + ")");

		if (NetworkUtils.isOffline(getApplicationContext())) {
			Timber.i("Skipping update; offline");
			return;
		}
		if (!Intent.ACTION_SYNC.equals(intent.getAction())) {
			Timber.w("Invalid intent action: " + intent.getAction());
			return;
		}
		int syncType = intent.getIntExtra(KEY_SYNC_TYPE, SYNC_TYPE_UNKNOWN);
		int syncId = intent.getIntExtra(KEY_SYNC_ID, BggContract.INVALID_ID);
		String syncKey = intent.getStringExtra(KEY_SYNC_KEY);

		UpdateTask task = createUpdateTask(syncType, syncId, syncKey);
		if (task.isValid()) {
			executeTask(syncType, task);
		} else {
			postError(getString(R.string.sync_msg_invalid, task.getDescription(getApplicationContext())));
		}
	}

	private void executeTask(int syncType, @NonNull UpdateTask task) {
		final long startTime = signalStart(syncType);
		try {
			task.execute(this);
		} catch (Exception e) {
			Timber.e(e, "Error executing task");
			String message = createErrorMessage(task, e);
			maybeShowNotification(message);
			postError(message);
		} finally {
			signalEnd(startTime);
		}
	}

	private long signalStart(int syncType) {
		EventBus.getDefault().postSticky(new UpdateEvent(syncType));
		return System.currentTimeMillis();
	}

	private void signalEnd(long startTime) {
		Timber.d("Sync took " + (System.currentTimeMillis() - startTime) + "ms");
		EventBus.getDefault().removeStickyEvent(UpdateEvent.class);
		EventBus.getDefault().post(new UpdateCompleteEvent());
	}

	@NonNull
	private UpdateTask createUpdateTask(int syncType, int syncId, String syncKey) {
		UpdateTask task = new InvalidUpdateTask(syncType);
		switch (syncType) {
			case SYNC_TYPE_GAME:
				task = new SyncGame(syncId);
				break;
			case SYNC_TYPE_GAME_PLAYS:
				task = new SyncGamePlays(syncId);
				break;
			case SYNC_TYPE_GAME_COLLECTION:
				task = new SyncGameCollection(syncId);
				break;
			case SYNC_TYPE_BUDDY:
				task = new SyncBuddy(syncKey);
				break;
			case SYNC_TYPE_BUDDY_SELF:
				task = new SyncBuddySelf();
				break;
			case SYNC_TYPE_DESIGNER:
				task = new SyncDesigner(syncId);
				break;
			case SYNC_TYPE_ARTIST:
				task = new SyncArtist(syncId);
				break;
			case SYNC_TYPE_PUBLISHER:
				task = new SyncPublisher(syncId);
				break;
			case SYNC_TYPE_PLAYS_DATE:
				task = new SyncPlaysByDate(syncKey);
				break;
		}
		return task;
	}

	@NonNull
	private String createErrorMessage(@NonNull UpdateTask task, @NonNull Exception e) {
		String message = getString(R.string.sync_msg_error, task.getDescription(getApplicationContext()));
		String error = e.getLocalizedMessage();
		if (!TextUtils.isEmpty(error)) {
			message += "\n" + error;
		}
		return message;
	}

	private void maybeShowNotification(String message) {
		if (PreferencesUtils.getSyncShowNotifications(this)) {
			Builder builder = NotificationUtils
				.createNotificationBuilder(getApplicationContext(), R.string.title_error)
				.setCategory(NotificationCompat.CATEGORY_ERROR);
			builder.setContentText(message).setStyle(new NotificationCompat.BigTextStyle().bigText(message));
			NotificationUtils.notify(getApplicationContext(), NotificationUtils.TAG_UPDATE_ERROR, 0, builder);
		}
	}

	private void postError(String message) {
		Timber.w(message);
		EventBus.getDefault().post(new UpdateErrorEvent(message));
	}
}
