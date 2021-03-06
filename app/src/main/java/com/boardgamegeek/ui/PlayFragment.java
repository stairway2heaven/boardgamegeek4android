package com.boardgamegeek.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.support.v4.app.ListFragment;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v4.widget.SwipeRefreshLayout.OnRefreshListener;
import android.support.v7.graphics.Palette;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.AbsListView;
import android.widget.AbsListView.LayoutParams;
import android.widget.AbsListView.OnScrollListener;
import android.widget.BaseAdapter;
import android.widget.Chronometer;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.boardgamegeek.R;
import com.boardgamegeek.events.PlayDeletedEvent;
import com.boardgamegeek.events.PlaySentEvent;
import com.boardgamegeek.events.UpdateCompleteEvent;
import com.boardgamegeek.events.UpdateEvent;
import com.boardgamegeek.model.Play;
import com.boardgamegeek.model.Player;
import com.boardgamegeek.model.builder.PlayBuilder;
import com.boardgamegeek.model.persister.PlayPersister;
import com.boardgamegeek.provider.BggContract;
import com.boardgamegeek.provider.BggContract.Plays;
import com.boardgamegeek.service.UpdateService;
import com.boardgamegeek.ui.widget.PlayerRow;
import com.boardgamegeek.ui.widget.TimestampView;
import com.boardgamegeek.util.ActivityUtils;
import com.boardgamegeek.util.DateTimeUtils;
import com.boardgamegeek.util.DialogUtils;
import com.boardgamegeek.util.ImageUtils;
import com.boardgamegeek.util.ImageUtils.Callback;
import com.boardgamegeek.util.NotificationUtils;
import com.boardgamegeek.util.PreferencesUtils;
import com.boardgamegeek.util.PresentationUtils;
import com.boardgamegeek.util.UIUtils;
import com.boardgamegeek.util.fabric.PlayManipulationEvent;
import com.crashlytics.android.answers.Answers;
import com.crashlytics.android.answers.ShareEvent;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import butterknife.Unbinder;
import hugo.weaving.DebugLog;
import icepick.Icepick;
import icepick.State;

public class PlayFragment extends ListFragment implements LoaderCallbacks<Cursor>, OnRefreshListener {
	private static final int AGE_IN_DAYS_TO_REFRESH = 7;
	private static final int PLAY_QUERY_TOKEN = 0x01;
	private static final int PLAYER_QUERY_TOKEN = 0x02;

	private boolean isSyncing;
	private int playId = BggContract.INVALID_ID;
	private Play play = new Play();
	private String thumbnailUrl;
	private String imageUrl;

	private Unbinder unbinder;
	@BindView(R.id.swipe_refresh) SwipeRefreshLayout swipeRefreshLayout;
	@BindView(R.id.progress) View progressContainer;
	@BindView(R.id.list_container) View listContainer;
	@BindView(R.id.empty) TextView emptyView;
	@BindView(R.id.thumbnail) ImageView thumbnailView;
	@BindView(R.id.header) TextView gameNameView;
	@BindView(R.id.play_date) TextView dateView;
	@BindView(R.id.play_quantity) TextView quantityView;
	@BindView(R.id.length_root) View lengthContainer;
	@BindView(R.id.play_length) TextView lengthView;
	@BindView(R.id.timer_root) View timerContainer;
	@BindView(R.id.timer) Chronometer timerView;
	@BindView(R.id.location_root) View locationContainer;
	@BindView(R.id.play_location) TextView locationView;
	@BindView(R.id.play_incomplete) View incompleteView;
	@BindView(R.id.play_no_win_stats) View noWinStatsView;
	@BindView(R.id.play_comments) TextView commentsView;
	@BindView(R.id.play_comments_label) View commentsLabel;
	@BindView(R.id.play_players_label) View playersLabel;
	@BindView(R.id.updated) TimestampView updatedTimestampView;
	@BindView(R.id.play_id) TextView playIdView;
	@BindView(R.id.play_saved) TimestampView savedTimestampView;
	@BindView(R.id.play_unsynced_message) TextView notSyncedMessageView;
	private PlayerAdapter adapter;
	@State boolean hasBeenNotified;

	final private OnScrollListener onScrollListener = new OnScrollListener() {
		@Override
		public void onScrollStateChanged(AbsListView view, int scrollState) {
		}

		@Override
		public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
			if (swipeRefreshLayout != null) {
				int topRowVerticalPosition = (view == null || view.getChildCount() == 0) ? 0 : view.getChildAt(0).getTop();
				swipeRefreshLayout.setEnabled(firstVisibleItem == 0 && topRowVerticalPosition >= 0);
			}
		}
	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Icepick.restoreInstanceState(this, savedInstanceState);

		setHasOptionsMenu(true);

		final Intent intent = UIUtils.fragmentArgumentsToIntent(getArguments());
		playId = intent.getIntExtra(PlayActivity.KEY_PLAY_ID, BggContract.INVALID_ID);

		if (playId == BggContract.INVALID_ID) {
			return;
		}

		play = new Play(playId, intent.getIntExtra(PlayActivity.KEY_GAME_ID, BggContract.INVALID_ID),
			intent.getStringExtra(PlayActivity.KEY_GAME_NAME));

		thumbnailUrl = intent.getStringExtra(PlayActivity.KEY_THUMBNAIL_URL);
		imageUrl = intent.getStringExtra(PlayActivity.KEY_IMAGE_URL);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		ViewGroup rootView = (ViewGroup) inflater.inflate(R.layout.fragment_play, container, false);

		ListView playersView = (ListView) rootView.findViewById(android.R.id.list);
		playersView.setHeaderDividersEnabled(false);
		playersView.setFooterDividersEnabled(false);

		playersView.addHeaderView(View.inflate(getActivity(), R.layout.header_play, null), null, false);
		playersView.addFooterView(View.inflate(getActivity(), R.layout.footer_play, null), null, false);

		unbinder = ButterKnife.bind(this, rootView);

		if (swipeRefreshLayout != null) {
			swipeRefreshLayout.setOnRefreshListener(this);
			swipeRefreshLayout.setColorSchemeResources(PresentationUtils.getColorSchemeResources());
		}

		adapter = new PlayerAdapter();
		playersView.setAdapter(adapter);

		getLoaderManager().restartLoader(PLAY_QUERY_TOKEN, null, this);

		return rootView;
	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		getListView().setOnScrollListener(onScrollListener);
	}

	@DebugLog
	@Override
	public void onStart() {
		super.onStart();
		EventBus.getDefault().register(this);
	}

	@Override
	public void onResume() {
		super.onResume();
		if (play != null && play.hasStarted()) {
			NotificationUtils.launchPlayingNotification(getActivity(), play, thumbnailUrl, imageUrl);
			hasBeenNotified = true;
		}
	}

	@DebugLog
	@Override
	public void onStop() {
		EventBus.getDefault().unregister(this);
		super.onStop();
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		if (unbinder != null) unbinder.unbind();
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		Icepick.saveInstanceState(this, outState);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.play, menu);
	}

	@Override
	public void onPrepareOptionsMenu(Menu menu) {
		menu.findItem(R.id.menu_send).setVisible(play.syncStatus == Play.SYNC_STATUS_IN_PROGRESS);
		MenuItem menuItem = menu.findItem(R.id.menu_discard);
		if (menuItem != null) {
			menuItem.setVisible(play.hasBeenSynced() && play.syncStatus == Play.SYNC_STATUS_IN_PROGRESS);
		}
		menu.findItem(R.id.menu_share).setEnabled(play.syncStatus == Play.SYNC_STATUS_SYNCED);

		super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.menu_discard:
				DialogUtils.createConfirmationDialog(getActivity(), R.string.are_you_sure_refresh_message,
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							PlayManipulationEvent.log("Discard", play.gameName);
							save(Play.SYNC_STATUS_SYNCED);
						}
					}).show();
				return true;
			case R.id.menu_edit:
				PlayManipulationEvent.log("Edit", play.gameName);
				ActivityUtils.editPlay(getActivity(), play.playId, play.gameId, play.gameName, thumbnailUrl, imageUrl);
				return true;
			case R.id.menu_send:
				save(Play.SYNC_STATUS_PENDING_UPDATE);
				EventBus.getDefault().post(new PlaySentEvent());
				return true;
			case R.id.menu_delete: {
				DialogUtils.createConfirmationDialog(getActivity(), R.string.are_you_sure_delete_play,
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							if (play.hasStarted()) {
								cancelNotification();
							}
							play.end(); // this prevents the timer from reappearing
							save(Play.SYNC_STATUS_PENDING_DELETE);
							EventBus.getDefault().post(new PlayDeletedEvent());
						}
					}).show();
				return true;
			}
			case R.id.menu_rematch:
				PlayManipulationEvent.log("Rematch", play.gameName);
				ActivityUtils.rematch(getActivity(), play.playId, play.gameId, play.gameName, thumbnailUrl, imageUrl);
				getActivity().finish(); // don't want to show the "old" play upon return
				return true;
			case R.id.menu_share:
				ActivityUtils.share(getActivity(), play.toShortDescription(getActivity()), play.toLongDescription(getActivity()), R.string.share_play_title);
				Answers.getInstance().logShare(new ShareEvent()
					.putContentType("Play")
					.putContentName(play.toShortDescription(getActivity()))
					.putContentId(String.valueOf(play.playId)));
				return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@DebugLog
	@Override
	public void onRefresh() {
		triggerRefresh();
	}

	@SuppressWarnings("unused")
	@DebugLog
	@Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
	public void onEvent(UpdateEvent event) {
		if (event.getType() == UpdateService.SYNC_TYPE_GAME_PLAYS) {
			isSyncing = true;
			updateRefreshStatus();
		}
	}

	@SuppressWarnings({ "unused", "UnusedParameters" })
	@DebugLog
	@Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
	public void onEvent(UpdateCompleteEvent event) {
		isSyncing = false;
		updateRefreshStatus();
	}

	@SuppressWarnings("unused")
	@DebugLog
	private void updateRefreshStatus() {
		if (swipeRefreshLayout != null) {
			swipeRefreshLayout.post(new Runnable() {
				@Override
				public void run() {
					if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(isSyncing);
				}
			});
		}
	}

	@OnClick(R.id.header_container)
	void viewGame() {
		ActivityUtils.launchGame(getActivity(), play.gameId, play.gameName);
	}

	@OnClick(R.id.timer_end)
	void onTimerClick() {
		ActivityUtils.endPlay(getActivity(), play.playId, play.gameId, play.gameName, thumbnailUrl, imageUrl);
	}

	@Override
	public Loader<Cursor> onCreateLoader(int id, Bundle data) {
		CursorLoader loader = null;
		switch (id) {
			case PLAY_QUERY_TOKEN:
				loader = new CursorLoader(getActivity(), Plays.buildPlayUri(playId), PlayBuilder.PLAY_PROJECTION, null, null, null);
				break;
			case PLAYER_QUERY_TOKEN:
				loader = new CursorLoader(getActivity(), Plays.buildPlayerUri(playId), PlayBuilder.PLAYER_PROJECTION, null, null, null);
				break;
		}
		if (loader != null) {
			loader.setUpdateThrottle(1000);
		}
		return loader;
	}

	@Override
	public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
		if (getActivity() == null) {
			return;
		}

		switch (loader.getId()) {
			case PLAY_QUERY_TOKEN:
				if (onPlayQueryComplete(cursor)) {
					showList();
				}
				break;
			case PLAYER_QUERY_TOKEN:
				PlayBuilder.addPlayers(cursor, play);
				playersLabel.setVisibility(play.getPlayers().size() == 0 ? View.GONE : View.VISIBLE);
				adapter.notifyDataSetChanged();
				maybeShowNotification();
				showList();
				break;
			default:
				if (cursor != null) {
					cursor.close();
				}
				break;
		}
	}

	private void showList() {
		progressContainer.startAnimation(AnimationUtils.loadAnimation(getActivity(), android.R.anim.fade_out));
		progressContainer.setVisibility(View.GONE);
		listContainer.startAnimation(AnimationUtils.loadAnimation(getActivity(), android.R.anim.fade_in));
		listContainer.setVisibility(View.VISIBLE);
	}

	@Override
	public void onLoaderReset(Loader<Cursor> loader) {
	}

	public void setNewPlayId(int playId) {
		this.playId = playId;
		if (isAdded()) {
			getLoaderManager().restartLoader(PLAY_QUERY_TOKEN, null, this);
		}
	}

	/**
	 * @return true if the we're done loading
	 */
	private boolean onPlayQueryComplete(Cursor cursor) {
		if (cursor == null || !cursor.moveToFirst()) {
			int newPlayId = PreferencesUtils.getNewPlayId(getActivity(), playId);
			if (newPlayId != BggContract.INVALID_ID) {
				setNewPlayId(newPlayId);
				return false;
			}
			emptyView.setText(String.format(getResources().getString(R.string.empty_play), String.valueOf(playId)));
			emptyView.setVisibility(View.VISIBLE);
			return true;
		}
		emptyView.setVisibility(View.GONE);

		ImageUtils.safelyLoadImage(thumbnailView, imageUrl, new Callback() {
			@Override
			public void onSuccessfulImageLoad(Palette palette) {
				if (gameNameView != null && isAdded()) {
					gameNameView.setBackgroundResource(R.color.black_overlay_light);
				}
			}

			@Override
			public void onFailedImageLoad() {
			}
		});

		List<Player> players = play.getPlayers();
		play = PlayBuilder.fromCursor(cursor);
		play.setPlayers(players);

		gameNameView.setText(play.gameName);

		dateView.setText(play.getDateForDisplay(getActivity()));

		quantityView.setText(getString(R.string.times_suffix, play.quantity));
		quantityView.setVisibility((play.quantity == 1) ? View.GONE : View.VISIBLE);

		if (play.length > 0) {
			lengthContainer.setVisibility(View.VISIBLE);
			lengthView.setText(DateTimeUtils.describeMinutes(getActivity(), play.length));
			lengthView.setVisibility(View.VISIBLE);
			timerContainer.setVisibility(View.GONE);
			timerView.stop();
		} else if (play.hasStarted()) {
			lengthContainer.setVisibility(View.VISIBLE);
			lengthView.setVisibility(View.GONE);
			timerContainer.setVisibility(View.VISIBLE);
			UIUtils.startTimerWithSystemTime(timerView, play.startTime);
		} else {
			lengthContainer.setVisibility(View.GONE);
		}

		locationView.setText(play.location);
		locationContainer.setVisibility(TextUtils.isEmpty(play.location) ? View.GONE : View.VISIBLE);

		incompleteView.setVisibility(play.Incomplete() ? View.VISIBLE : View.GONE);
		noWinStatsView.setVisibility(play.NoWinStats() ? View.VISIBLE : View.GONE);

		commentsView.setText(play.comments);
		commentsView.setVisibility(TextUtils.isEmpty(play.comments) ? View.GONE : View.VISIBLE);
		commentsLabel.setVisibility(TextUtils.isEmpty(play.comments) ? View.GONE : View.VISIBLE);

		updatedTimestampView.setVisibility((play.updated == 0) ? View.GONE : View.VISIBLE);
		updatedTimestampView.setTimestamp(play.updated);

		if (play.hasBeenSynced()) {
			playIdView.setText(String.format(getResources().getString(R.string.id_list_text), String.valueOf(play.playId)));
		}

		if (play.syncStatus != Play.SYNC_STATUS_SYNCED) {
			notSyncedMessageView.setVisibility(View.VISIBLE);
			savedTimestampView.setVisibility(View.VISIBLE);
			savedTimestampView.setTimestamp(play.saved);

			if (play.syncStatus == Play.SYNC_STATUS_IN_PROGRESS) {
				if (play.hasBeenSynced()) {
					notSyncedMessageView.setText(R.string.sync_editing);
				} else {
					notSyncedMessageView.setText(R.string.sync_draft);
				}
			} else if (play.syncStatus == Play.SYNC_STATUS_PENDING_UPDATE) {
				notSyncedMessageView.setText(R.string.sync_pending_update);
			} else if (play.syncStatus == Play.SYNC_STATUS_PENDING_DELETE) {
				notSyncedMessageView.setText(R.string.sync_pending_delete);
			}
		} else {
			notSyncedMessageView.setVisibility(View.GONE);
			savedTimestampView.setVisibility(View.GONE);
		}

		getActivity().supportInvalidateOptionsMenu();
		getLoaderManager().restartLoader(PLAYER_QUERY_TOKEN, null, this);

		if (play.hasBeenSynced()
			&& (play.updated == 0 || DateTimeUtils.howManyDaysOld(play.updated) > AGE_IN_DAYS_TO_REFRESH)) {
			triggerRefresh();
		}

		return false;
	}

	private void maybeShowNotification() {
		if (play.hasStarted()) {
			NotificationUtils.launchPlayingNotification(getActivity(), play, thumbnailUrl, imageUrl);
		} else if (hasBeenNotified) {
			cancelNotification();
		}
	}

	private void cancelNotification() {
		NotificationUtils.cancel(getActivity(), NotificationUtils.TAG_PLAY_TIMER, playId);
	}

	private void triggerRefresh() {
		UpdateService.start(getActivity(), UpdateService.SYNC_TYPE_GAME_PLAYS, play.gameId);
	}

	private void save(int status) {
		String action = "Save";
		if (status == Play.SYNC_STATUS_PENDING_DELETE) {
			action = "Delete";
		} else if (status == Play.SYNC_STATUS_PENDING_UPDATE) {
			action = "SaveDraft";
		}
		PlayManipulationEvent.log(action, play.gameName);
		play.syncStatus = status;
		new PlayPersister(getActivity()).save(play);
		triggerRefresh();
	}

	private class PlayerAdapter extends BaseAdapter {
		@Override
		public boolean isEnabled(int position) {
			return false;
		}

		@Override
		public int getCount() {
			return play.getPlayerCount();
		}

		@Override
		public Object getItem(int position) {
			return play.getPlayers().get(position);
		}

		@Override
		public long getItemId(int position) {
			return position;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			PlayerRow row = new PlayerRow(getActivity());
			row.setLayoutParams(new ListView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
			row.setPlayer((Player) getItem(position));
			return row;
		}
	}
}
