package com.boardgamegeek.ui;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.net.Uri.Builder;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.design.widget.Snackbar;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.CursorAdapter;
import android.support.v7.widget.Toolbar;
import android.support.v7.widget.Toolbar.OnMenuItemClickListener;
import android.text.TextUtils;
import android.util.Pair;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.AbsListView.MultiChoiceModeListener;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.boardgamegeek.R;
import com.boardgamegeek.auth.Authenticator;
import com.boardgamegeek.events.CollectionCountChangedEvent;
import com.boardgamegeek.events.CollectionSortChangedEvent;
import com.boardgamegeek.events.CollectionViewRequestedEvent;
import com.boardgamegeek.events.GameSelectedEvent;
import com.boardgamegeek.events.GameShortcutCreatedEvent;
import com.boardgamegeek.filterer.CollectionFilterer;
import com.boardgamegeek.filterer.CollectionFiltererFactory;
import com.boardgamegeek.interfaces.CollectionView;
import com.boardgamegeek.provider.BggContract.Collection;
import com.boardgamegeek.provider.BggContract.CollectionViewFilters;
import com.boardgamegeek.provider.BggContract.CollectionViews;
import com.boardgamegeek.provider.BggContract.Games;
import com.boardgamegeek.service.SyncService;
import com.boardgamegeek.sorter.CollectionSorter;
import com.boardgamegeek.sorter.CollectionSorterFactory;
import com.boardgamegeek.ui.dialog.CollectionFilterDialog;
import com.boardgamegeek.ui.dialog.CollectionFilterDialogFactory;
import com.boardgamegeek.ui.dialog.CollectionFilterDialogFragment;
import com.boardgamegeek.ui.dialog.CollectionSortDialogFragment;
import com.boardgamegeek.ui.dialog.DeleteView;
import com.boardgamegeek.ui.dialog.SaveView;
import com.boardgamegeek.util.ActivityUtils;
import com.boardgamegeek.util.PreferencesUtils;
import com.boardgamegeek.util.PresentationUtils;
import com.boardgamegeek.util.RandomUtils;
import com.boardgamegeek.util.ResolverUtils;
import com.boardgamegeek.util.StringUtils;
import com.boardgamegeek.util.UIUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import butterknife.ButterKnife;
import butterknife.InjectView;
import de.greenrobot.event.EventBus;
import hugo.weaving.DebugLog;
import se.emilsjolander.stickylistheaders.StickyListHeadersAdapter;
import timber.log.Timber;

public class CollectionFragment extends StickyHeaderListFragment implements LoaderCallbacks<Cursor>, CollectionView, MultiChoiceModeListener {
	private static final String STATE_SELECTED_ID = "STATE_SELECTED_ID";
	private static final String STATE_VIEW_ID = "STATE_VIEW_ID";
	private static final String STATE_VIEW_NAME = "STATE_VIEW_NAME";
	private static final String STATE_SORT_TYPE = "STATE_SORT_TYPE";
	private static final String STATE_FILTER_TYPES = "STATE_FILTER_TYPES";
	private static final String STATE_FILTER_DATA = "STATE_FILTER_DATA";
	private static final int TIME_HINT_UPDATE_INTERVAL = 30000; // 30 sec

	@SuppressWarnings("unused") @InjectView(R.id.frame_container) ViewGroup frameContainer;
	@SuppressWarnings("unused") @InjectView(R.id.footer_container) ViewGroup footerContainer;
	@SuppressWarnings("unused") @InjectView(R.id.filter_linear_layout) LinearLayout filterButtonContainer;
	@SuppressWarnings("unused") @InjectView(R.id.filter_scroll_view) View filterButtonScroll;
	@SuppressWarnings("unused") @InjectView(R.id.toolbar_footer) Toolbar footerToolbar;
	@SuppressWarnings("unused") @InjectView(R.id.row_count) TextView rowCountView;
	@SuppressWarnings("unused") @InjectView(R.id.sort_description) TextView sortDescriptionView;

	@NonNull private Handler timeUpdateHandler = new Handler();
	@Nullable private Runnable timeUpdateRunnable = null;
	private int selectedCollectionId;
	private CollectionAdapter adapter;
	private long viewId;
	@Nullable private String viewName = "";
	private CollectionSorter sorter;
	private final List<CollectionFilterer> filters = new ArrayList<>();
	private String defaultWhereClause;
	private boolean isCreatingShortcut;
	private final LinkedHashSet<Integer> selectedPositions = new LinkedHashSet<>();
	private android.view.MenuItem logPlayMenuItem;
	private android.view.MenuItem logPlayQuickMenuItem;
	private android.view.MenuItem bggLinkMenuItem;
	private CollectionSorterFactory collectionSorterFactory;

	@Override
	@DebugLog
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		timeUpdateHandler = new Handler();
		if (savedInstanceState != null) {
			selectedCollectionId = savedInstanceState.getInt(STATE_SELECTED_ID);
			viewId = savedInstanceState.getLong(STATE_VIEW_ID);
			viewName = savedInstanceState.getString(STATE_VIEW_NAME);

			filters.clear();
			ArrayList<Integer> types = savedInstanceState.getIntegerArrayList(STATE_FILTER_TYPES);
			ArrayList<String> data = savedInstanceState.getStringArrayList(STATE_FILTER_DATA);
			if (types != null && data != null) {
				if (types.size() != data.size()) {
					Timber.w("Mismatched size of arrays: types.size() = %1$s; data.size() = %2@s", types.size(), data.size());
				} else {
					CollectionFiltererFactory factory = new CollectionFiltererFactory(getActivity());
					for (int i = 0; i < types.size(); i++) {
						final Integer filterType = types.get(i);
						CollectionFilterer filterer = factory.create(filterType);
						if (filterer == null) {
							Timber.w("Couldn't create filterer with type " + filterType);
						} else {
							filterer.setData(data.get(i));
							filters.add(filterer);
						}
					}
				}
			}
		}

		final Intent intent = UIUtils.fragmentArgumentsToIntent(getArguments());
		isCreatingShortcut = "android.intent.action.CREATE_SHORTCUT".equals(intent.getAction());
	}

	@Override
	@DebugLog
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_collection, container, false);
		ButterKnife.inject(this, view);
		return view;
	}

	@Override
	@DebugLog
	public void onViewCreated(View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		if (isCreatingShortcut) {
			Snackbar.make(frameContainer, R.string.msg_shortcut_create, Snackbar.LENGTH_LONG).show();
		}

		footerToolbar.inflateMenu(R.menu.collection_fragment);
		footerToolbar.setOnMenuItemClickListener(footerMenuListener);
		invalidateMenu();

		setEmptyText();
	}

	@Override
	@DebugLog
	public void onActivityCreated(@Nullable Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);

		int sortType = CollectionSorterFactory.TYPE_DEFAULT;
		if (savedInstanceState != null) {
			sortType = savedInstanceState.getInt(STATE_SORT_TYPE);
		}
		sorter = getCollectionSorter(sortType);
		if (savedInstanceState != null || isCreatingShortcut) {
			requery();
		}
		if (getListView() != null) {
			final ListView listView = getListView().getWrappedList();
			listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
			listView.setMultiChoiceModeListener(this);
		}
	}

	private CollectionSorter getCollectionSorter(int sortType) {
		if (collectionSorterFactory == null) {
			collectionSorterFactory = new CollectionSorterFactory(getActivity());
		}
		return collectionSorterFactory.create(sortType);
	}

	@DebugLog
	private void requery() {
		getLoaderManager().restartLoader(Query._TOKEN, null, this);
	}

	@Override
	@DebugLog
	public void onResume() {
		super.onResume();
		if (timeUpdateRunnable != null) {
			timeUpdateHandler.postDelayed(timeUpdateRunnable, TIME_HINT_UPDATE_INTERVAL);
		}
	}

	@Override
	@DebugLog
	public void onPause() {
		super.onPause();
		if (timeUpdateRunnable != null) {
			timeUpdateHandler.removeCallbacks(timeUpdateRunnable);
		}
	}

	@Override
	@DebugLog
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putLong(STATE_VIEW_ID, viewId);
		outState.putString(STATE_VIEW_NAME, viewName);
		outState.putInt(STATE_SORT_TYPE, sorter == null ? CollectionSorterFactory.TYPE_UNKNOWN : sorter.getType());

		ArrayList<Integer> types = new ArrayList<>();
		ArrayList<String> data = new ArrayList<>();
		for (CollectionFilterer filterer : filters) {
			types.add(filterer.getType());
			data.add(filterer.flatten());
		}
		outState.putIntegerArrayList(STATE_FILTER_TYPES, types);
		outState.putStringArrayList(STATE_FILTER_DATA, data);

		if (selectedCollectionId > 0) {
			outState.putInt(STATE_SELECTED_ID, selectedCollectionId);
		}
	}

	@Override
	@DebugLog
	public void onListItemClick(View view, int position, long id) {
		final Cursor cursor = (Cursor) adapter.getItem(position);
		final int gameId = cursor.getInt(Query.GAME_ID);
		final String gameName = cursor.getString(Query.COLLECTION_NAME);
		final String thumbnailUrl = cursor.getString(Query.THUMBNAIL_URL);
		if (isCreatingShortcut) {
			EventBus.getDefault().post(new GameShortcutCreatedEvent(gameId, gameName, thumbnailUrl));
		} else {
			EventBus.getDefault().post(new GameSelectedEvent(gameId, gameName));
			setSelectedGameId(gameId);
		}
	}

	@DebugLog
	private void invalidateMenu() {
		final Menu menu = footerToolbar.getMenu();
		if (isCreatingShortcut) {
			menu.findItem(R.id.menu_collection_random_game).setVisible(false);
			menu.findItem(R.id.menu_collection_view_save).setVisible(false);
			menu.findItem(R.id.menu_collection_view_delete).setVisible(false);
		} else {
			menu.findItem(R.id.menu_collection_random_game).setVisible(true);
			menu.findItem(R.id.menu_collection_view_save).setVisible(true);
			menu.findItem(R.id.menu_collection_view_delete).setVisible(true);

			final boolean hasFiltersApplied = (filters.size() > 0);
			final boolean hasSortApplied = sorter != null && sorter.getType() != CollectionSorterFactory.TYPE_DEFAULT;
			final boolean hasViews = getActivity() != null && ResolverUtils.getCount(getActivity().getContentResolver(), CollectionViews.CONTENT_URI) > 0;
			final boolean hasItems = adapter != null && adapter.getCount() > 0;

			menu.findItem(R.id.menu_collection_view_save).setEnabled(hasFiltersApplied || hasSortApplied);
			menu.findItem(R.id.menu_collection_view_delete).setEnabled(hasViews);
			menu.findItem(R.id.menu_collection_random_game).setEnabled(hasItems);
		}
	}

	@NonNull OnMenuItemClickListener footerMenuListener = new OnMenuItemClickListener() {
		@DebugLog
		@Override
		public boolean onMenuItemClick(@NonNull MenuItem item) {
			switch (item.getItemId()) {
				case R.id.menu_collection_random_game:
					final Cursor cursor = (Cursor) adapter.getItem(RandomUtils.getRandom().nextInt(adapter.getCount()));
					ActivityUtils.launchGame(getActivity(), cursor.getInt(Query.GAME_ID), cursor.getString(Query.COLLECTION_NAME));
					return true;
				case R.id.menu_collection_view_save:
					SaveView.createDialog(getActivity(), CollectionFragment.this, viewName, sorter, filters);
					return true;
				case R.id.menu_collection_view_delete:
					DeleteView.createDialog(getActivity(), CollectionFragment.this);
					return true;
				case R.id.menu_collection_sort:
					final CollectionSortDialogFragment sortFragment =
						CollectionSortDialogFragment.newInstance(swipeRefreshLayout, new CollectionSortDialogFragment.Listener() {
							@Override
							public void onSortSelected(int sortType) {
								setSort(sortType);
							}
						});
					sortFragment.setSelection(sorter.getType());
					sortFragment.show(getFragmentManager(), "sort");
					return true;
				case R.id.menu_collection_filter:
					final CollectionFilterDialogFragment filterFragment =
						CollectionFilterDialogFragment.newInstance(swipeRefreshLayout, new CollectionFilterDialogFragment.Listener() {
							@Override
							public void onFilterSelected(int filterType) {
								launchFilterDialog(filterType);
							}
						});
					for (CollectionFilterer filter : filters) {
						filterFragment.addEnabledFilter(filter.getType());
					}
					filterFragment.show(getFragmentManager(), "filter");
					return true;
			}
			return launchFilterDialog(item.getItemId());
		}
	};

	@DebugLog
	@Override
	protected void triggerRefresh() {
		SyncService.sync(getActivity(), SyncService.FLAG_SYNC_COLLECTION);
	}

	@DebugLog
	@Override
	protected int getSyncType() {
		return SyncService.FLAG_SYNC_COLLECTION;
	}

	@Nullable
	@Override
	@DebugLog
	public Loader<Cursor> onCreateLoader(int id, Bundle data) {
		CursorLoader loader = null;
		if (id == Query._TOKEN) {
			StringBuilder where = new StringBuilder();
			String[] args = {};
			Builder uriBuilder = Collection.CONTENT_URI.buildUpon();
			if (viewId == 0 && filters.size() == 0) {
				where.append(buildDefaultWhereClause());
			} else {
				for (CollectionFilterer filter : filters) {
					if (filter != null) {
						if (!TextUtils.isEmpty(filter.getSelection())) {
							if (where.length() > 0) {
								where.append(" AND ");
							}
							where.append("(").append(filter.getSelection()).append(")");
							args = StringUtils.concatenate(args, filter.getSelectionArgs());
						}
					}
				}
			}
			Uri mUri = uriBuilder.build();
			loader = new CursorLoader(getActivity(), mUri, sorter == null ? Query.PROJECTION : StringUtils.unionArrays(
				Query.PROJECTION, sorter.getColumns()), where.toString(), args, sorter == null ? null
				: sorter.getOrderByClause());
		} else if (id == ViewQuery._TOKEN) {
			if (viewId > 0) {
				loader = new CursorLoader(getActivity(), CollectionViews.buildViewFilterUri(viewId),
					ViewQuery.PROJECTION, null, null, null);
			}
		}
		return loader;
	}

	private String buildDefaultWhereClause() {
		if (!TextUtils.isEmpty(defaultWhereClause)) {
			return defaultWhereClause;
		}
		String[] statuses = PreferencesUtils.getSyncStatuses(getActivity());
		if (statuses == null) {
			defaultWhereClause = "";
			return defaultWhereClause;
		}
		StringBuilder where = new StringBuilder();
		for (String status : statuses) {
			if (TextUtils.isEmpty(status)) {
				continue;
			}
			if (where.length() > 0) {
				where.append(" OR ");
			}
			switch (status) {
				case "own":
					where.append(Collection.STATUS_OWN).append("=1");
					break;
				case "played":
					where.append(Games.NUM_PLAYS).append(">0");
					break;
				case "rated":
					where.append(Collection.RATING).append(">0");
					break;
				case "comment":
					where.append(Collection.COMMENT).append("='' OR ").append(Collection.COMMENT).append(" IS NULL");
					break;
				case "prevowned":
					where.append(Collection.STATUS_PREVIOUSLY_OWNED).append("=1");
					break;
				case "trade":
					where.append(Collection.STATUS_FOR_TRADE).append("=1");
					break;
				case "want":
					where.append(Collection.STATUS_WANT).append("=1");
					break;
				case "wanttobuy":
					where.append(Collection.STATUS_WANT_TO_BUY).append("=1");
					break;
				case "wishlist":
					where.append(Collection.STATUS_WISHLIST).append("=1");
					break;
				case "wanttoplay":
					where.append(Collection.STATUS_WANT_TO_PLAY).append("=1");
					break;
				case "preordered":
					where.append(Collection.STATUS_PREORDERED).append("=1");
					break;
				case "hasparts":
					where.append(Collection.HASPARTS_LIST).append("='' OR ").append(Collection.HASPARTS_LIST).append(" IS NULL");
					break;
				case "wantparts":
					where.append(Collection.WANTPARTS_LIST).append("='' OR ").append(Collection.WANTPARTS_LIST).append(" IS NULL");
					break;
			}
		}
		defaultWhereClause = where.toString();
		return defaultWhereClause;
	}

	@Override
	@DebugLog
	public void onLoadFinished(@NonNull Loader<Cursor> loader, @NonNull Cursor cursor) {
		if (getActivity() == null) {
			return;
		}

		int token = loader.getId();
		if (token == Query._TOKEN) {
			if (adapter == null) {
				adapter = new CollectionAdapter(getActivity());
				setListAdapter(adapter);
			} else {
				setProgressShown(false);
			}
			adapter.changeCursor(cursor);
			initializeTimeBasedUi();
			restoreScrollState();

			final int rowCount = cursor.getCount();
			final String sortDescription = sorter == null ? "" :
				String.format(getActivity().getString(R.string.sort_description), sorter.getDescription());
			rowCountView.setText(String.valueOf(rowCount));
			sortDescriptionView.setText(sortDescription);
			EventBus.getDefault().post(new CollectionCountChangedEvent(rowCount));
			EventBus.getDefault().post(new CollectionSortChangedEvent(sortDescription));

			bindFilterButtons();
			invalidateMenu();
		} else if (token == ViewQuery._TOKEN) {
			if (cursor.moveToFirst()) {
				viewName = cursor.getString(ViewQuery.NAME);
				sorter = getCollectionSorter(cursor.getInt(ViewQuery.SORT_TYPE));
				filters.clear();
				do {
					CollectionFilterer filter = new CollectionFiltererFactory(getActivity()).create(cursor.getInt(ViewQuery.TYPE));
					if (filter != null) {
						filter.setData(cursor.getString(ViewQuery.DATA));
						filters.add(filter);
					}
				} while (cursor.moveToNext());
				setEmptyText();
				requery();
			}
		} else {
			Timber.d("Query complete, Not Actionable: " + token);
			cursor.close();
		}
	}

	@Override
	@DebugLog
	public void onLoaderReset(Loader<Cursor> loader) {
		if (adapter != null) {
			adapter.changeCursor(null);
		}
	}

	@Override
	@DebugLog
	protected void onScrollDown() {
		if (footerContainer.getVisibility() != View.VISIBLE) {
			footerContainer.startAnimation(AnimationUtils.loadAnimation(getActivity(), R.anim.slide_up));
			footerContainer.setVisibility(View.VISIBLE);
		}
	}

	@Override
	@DebugLog
	protected void onScrollUp() {
		if (footerContainer.getVisibility() != View.GONE) {
			footerContainer.startAnimation(AnimationUtils.loadAnimation(getActivity(), R.anim.slide_down));
			footerContainer.setVisibility(View.GONE);
		}
	}

	private void initializeTimeBasedUi() {
		updateTimeBasedUi();
		if (timeUpdateRunnable != null) {
			timeUpdateHandler.removeCallbacks(timeUpdateRunnable);
		}
		timeUpdateRunnable = new Runnable() {
			@Override
			public void run() {
				updateTimeBasedUi();
				timeUpdateHandler.postDelayed(timeUpdateRunnable, TIME_HINT_UPDATE_INTERVAL);
			}
		};
		timeUpdateHandler.postDelayed(timeUpdateRunnable, TIME_HINT_UPDATE_INTERVAL);
	}

	@DebugLog
	private void updateTimeBasedUi() {
		if (adapter != null) {
			adapter.notifyDataSetChanged();
		}
	}

	@DebugLog
	private void setSelectedGameId(int id) {
		selectedCollectionId = id;
		if (adapter != null) {
			adapter.notifyDataSetChanged();
		}
	}

	@Override
	@DebugLog
	public void removeFilter(int type) {
		for (CollectionFilterer filter : filters) {
			if (filter.getType() == type) {
				filters.remove(filter);
				break;
			}
		}
		setEmptyText();
		resetScrollState();
		requery();
	}

	@Override
	@DebugLog
	public void addFilter(@NonNull CollectionFilterer filter) {
		filters.remove(filter);
		if (filter.isValid()) {
			filters.add(filter);
		}
		setEmptyText();
		resetScrollState();
		requery();
	}

	@DebugLog
	private void setEmptyText() {
		@StringRes int resId = R.string.empty_collection;
		if (!hasEverSynced()) {
			resId = R.string.empty_collection_sync_never;
		} else if (hasFiltersApplied()) {
			resId = R.string.empty_collection_filter_on;
		} else if (!hasSyncSettings()) {
			resId = R.string.empty_collection_sync_off;
		}
		setEmptyText(getString(resId));
	}

	private boolean hasEverSynced() {
		return Authenticator.getLong(getContext(), SyncService.TIMESTAMP_COLLECTION_COMPLETE) != 0;
	}

	private boolean hasFiltersApplied() {
		return filters.size() > 0;
	}

	private boolean hasSyncSettings() {
		String[] statuses = PreferencesUtils.getSyncStatuses(getActivity());
		return statuses != null && statuses.length > 0;
	}

	@DebugLog
	private void setSort(int sortType) {
		if (sortType == CollectionSorterFactory.TYPE_UNKNOWN) {
			sortType = CollectionSorterFactory.TYPE_DEFAULT;
		}
		sorter = getCollectionSorter(sortType);
		resetScrollState();
		requery();
	}

	@Override
	@DebugLog
	public void createView(long id, String name) {
		Toast.makeText(getActivity(), R.string.msg_saved, Toast.LENGTH_SHORT).show();
		EventBus.getDefault().post(new CollectionViewRequestedEvent(id));
	}

	@Override
	@DebugLog
	public void deleteView(long id) {
		Toast.makeText(getActivity(), R.string.msg_collection_view_deleted, Toast.LENGTH_SHORT).show();
		if (viewId == id) {
			EventBus.getDefault().post(new CollectionViewRequestedEvent(PreferencesUtils.getViewDefaultId(getActivity())));
		}
	}

	@DebugLog
	private CollectionFilterer findFilter(int type) {
		for (CollectionFilterer filter : filters) {
			if (filter != null && filter.getType() == type) {
				return filter;
			}
		}
		return null;
	}

	@DebugLog
	private void bindFilterButtons() {
		filterButtonContainer.removeAllViews();

		final LayoutInflater layoutInflater = getLayoutInflater(null);
		for (CollectionFilterer filter : filters) {
			if (filter != null && !TextUtils.isEmpty(filter.getDisplayText())) {
				filterButtonContainer.addView(createFilterButton(layoutInflater, filter.getType(), filter.getDisplayText()));
			}
		}

		filterButtonScroll.setVisibility(filterButtonContainer.getChildCount() > 0 ? View.VISIBLE : View.GONE);
	}

	@NonNull
	@DebugLog
	private Button createFilterButton(@NonNull LayoutInflater layoutInflater, final int type, String text) {
		final Button button = (Button) layoutInflater.inflate(R.layout.widget_button_filter, filterButtonContainer, false);
		button.setText(text);
		button.setTag(type);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		int margin = getResources().getDimensionPixelSize(R.dimen.padding_half);
		params.setMargins(0, margin, margin, 0);
		button.setLayoutParams(params);
		button.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(@NonNull View v) {
				launchFilterDialog((Integer) v.getTag());
			}
		});
		button.setOnLongClickListener(new View.OnLongClickListener() {
			@Override
			public boolean onLongClick(View v) {
				removeFilter(type);
				return true;
			}
		});
		return button;
	}

	@DebugLog
	private boolean launchFilterDialog(int filterType) {
		CollectionFilterDialogFactory factory = new CollectionFilterDialogFactory();
		CollectionFilterDialog dialog = factory.create(getActivity(), filterType);
		if (dialog != null) {
			dialog.createDialog(getActivity(), this, findFilter(filterType));
			return true;
		} else {
			Timber.w("Couldn't find a filter dialog of type " + filterType);
			return false;
		}
	}

	@DebugLog
	public long getViewId() {
		return viewId;
	}

	@DebugLog
	public void setView(long viewId) {
		if (this.viewId != viewId) {
			setProgressShown(true);
			this.viewId = viewId;
			resetScrollState();
			getLoaderManager().restartLoader(ViewQuery._TOKEN, null, this);
		}
	}

	@DebugLog
	public void clearView() {
		setProgressShown(true);
		viewId = 0;
		viewName = "";
		resetScrollState();
		filters.clear();
		sorter = getCollectionSorter(CollectionSorterFactory.TYPE_DEFAULT);
		requery();
	}

	private class CollectionAdapter extends CursorAdapter implements StickyListHeadersAdapter {
		@NonNull private final LayoutInflater mInflater;

		@DebugLog
		public CollectionAdapter(Context context) {
			super(context, null, false);
			mInflater = getActivity().getLayoutInflater();
		}

		@Override
		@DebugLog
		public View newView(Context context, Cursor cursor, ViewGroup parent) {
			View row = mInflater.inflate(R.layout.row_collection, parent, false);
			ViewHolder holder = new ViewHolder(row);
			row.setTag(holder);
			return row;
		}

		@Override
		@DebugLog
		public void bindView(@NonNull View view, Context context, @NonNull Cursor cursor) {
			ViewHolder holder = (ViewHolder) view.getTag();

			int collectionId = cursor.getInt(Query.COLLECTION_ID);
			int year = cursor.getInt(Query.COLLECTION_YEAR_PUBLISHED);
			if (year == 0) {
				year = cursor.getInt(Query.YEAR_PUBLISHED);
			}
			String collectionThumbnailUrl = cursor.getString(Query.COLLECTION_THUMBNAIL_URL);
			String thumbnailUrl = cursor.getString(Query.THUMBNAIL_URL);

			UIUtils.setActivatedCompat(view, collectionId == selectedCollectionId);

			holder.name.setText(cursor.getString(Query.COLLECTION_NAME));
			holder.year.setText(PresentationUtils.describeYear(getActivity(), year));
			holder.info.setText(sorter == null ? "" : sorter.getDisplayInfo(cursor));
			loadThumbnail(!TextUtils.isEmpty(collectionThumbnailUrl) ? collectionThumbnailUrl : thumbnailUrl,
				holder.thumbnail);
		}

		@Override
		public long getHeaderId(int position) {
			if (position < 0) {
				return 0;
			}
			return sorter.getHeaderId(getCursor(), position);
		}

		@Nullable
		@Override
		public View getHeaderView(int position, @Nullable View convertView, ViewGroup parent) {
			HeaderViewHolder holder;
			if (convertView == null) {
				holder = new HeaderViewHolder();
				convertView = mInflater.inflate(R.layout.row_header, parent, false);
				holder.text = (TextView) convertView.findViewById(android.R.id.title);
				convertView.setTag(holder);
			} else {
				holder = (HeaderViewHolder) convertView.getTag();
			}
			holder.text.setText(sorter.getHeaderText(getCursor(), position));
			return convertView;
		}

		class ViewHolder {
			@NonNull final TextView name;
			@NonNull final TextView year;
			@NonNull final TextView info;
			@NonNull final ImageView thumbnail;

			public ViewHolder(@NonNull View view) {
				name = (TextView) view.findViewById(R.id.name);
				year = (TextView) view.findViewById(R.id.year);
				info = (TextView) view.findViewById(R.id.info);
				thumbnail = (ImageView) view.findViewById(R.id.list_thumbnail);
			}
		}

		class HeaderViewHolder {
			TextView text;
		}
	}

	private interface Query {
		int _TOKEN = 0x01;
		String[] PROJECTION = { Collection._ID, Collection.COLLECTION_ID, Collection.COLLECTION_NAME,
			Collection.YEAR_PUBLISHED, Collection.GAME_NAME, Games.GAME_ID, Collection.COLLECTION_THUMBNAIL_URL,
			Collection.THUMBNAIL_URL, Collection.IMAGE_URL, Collection.COLLECTION_YEAR_PUBLISHED,
			Games.CUSTOM_PLAYER_SORT };

		// int _ID = 0;
		int COLLECTION_ID = 1;
		int COLLECTION_NAME = 2;
		int YEAR_PUBLISHED = 3;
		int GAME_NAME = 4;
		int GAME_ID = 5;
		int COLLECTION_THUMBNAIL_URL = 6;
		int THUMBNAIL_URL = 7;
		int IMAGE_URL = 8;
		int COLLECTION_YEAR_PUBLISHED = 9;
		int CUSTOM_PLAYER_SORT = 10;
	}

	private interface ViewQuery {
		int _TOKEN = 0x02;
		String[] PROJECTION = { CollectionViewFilters._ID, CollectionViewFilters.NAME, CollectionViewFilters.SORT_TYPE,
			CollectionViewFilters.TYPE, CollectionViewFilters.DATA, };

		// int _ID = 0;
		int NAME = 1;
		int SORT_TYPE = 2;
		int TYPE = 3;
		int DATA = 4;
	}

	@Override
	@DebugLog
	public boolean onCreateActionMode(@NonNull ActionMode mode, @NonNull android.view.Menu menu) {
		android.view.MenuInflater inflater = mode.getMenuInflater();
		inflater.inflate(R.menu.game_context, menu);
		logPlayMenuItem = menu.findItem(R.id.menu_log_play);
		logPlayQuickMenuItem = menu.findItem(R.id.menu_log_play_quick);
		bggLinkMenuItem = menu.findItem(R.id.menu_link);
		selectedPositions.clear();
		return true;
	}

	@Override
	@DebugLog
	public boolean onPrepareActionMode(ActionMode mode, android.view.Menu menu) {
		return false;
	}

	@Override
	@DebugLog
	public void onDestroyActionMode(ActionMode mode) {
	}

	@Override
	@DebugLog
	public void onItemCheckedStateChanged(@NonNull ActionMode mode, int position, long id, boolean checked) {
		if (checked) {
			selectedPositions.add(position);
		} else {
			selectedPositions.remove(position);
		}

		int count = selectedPositions.size();
		mode.setTitle(getResources().getQuantityString(R.plurals.msg_games_selected, count, count));

		logPlayMenuItem.setVisible(count == 1 && PreferencesUtils.showLogPlay(getActivity()));
		logPlayQuickMenuItem.setVisible(PreferencesUtils.showQuickLogPlay(getActivity()));
		bggLinkMenuItem.setVisible(count == 1);
	}

	@Override
	@DebugLog
	public boolean onActionItemClicked(@NonNull ActionMode mode, @NonNull android.view.MenuItem item) {
		if (!selectedPositions.iterator().hasNext()) {
			return false;
		}
		Cursor cursor = (Cursor) adapter.getItem(selectedPositions.iterator().next());
		int gameId = cursor.getInt(Query.GAME_ID);
		String gameName = cursor.getString(Query.GAME_NAME);
		String thumbnailUrl = cursor.getString(Query.THUMBNAIL_URL);
		String imageUrl = cursor.getString(Query.IMAGE_URL);
		boolean customPlayerSort = (cursor.getInt(Query.CUSTOM_PLAYER_SORT) == 1);
		switch (item.getItemId()) {
			case R.id.menu_log_play:
				mode.finish();
				ActivityUtils.logPlay(getActivity(), gameId, gameName, thumbnailUrl, imageUrl, customPlayerSort);
				return true;
			case R.id.menu_log_play_quick:
				mode.finish();
				String text = getResources().getQuantityString(R.plurals.msg_logging_plays, selectedPositions.size());
				Toast.makeText(getActivity(), text, Toast.LENGTH_SHORT).show();
				for (int position : selectedPositions) {
					Cursor c = (Cursor) adapter.getItem(position);
					ActivityUtils.logQuickPlay(getActivity(), c.getInt(Query.GAME_ID), c.getString(Query.GAME_NAME));
				}
				return true;
			case R.id.menu_share:
				mode.finish();
				if (selectedPositions.size() == 1) {
					ActivityUtils.shareGame(getActivity(), gameId, gameName);
				} else {
					List<Pair<Integer, String>> games = new ArrayList<>(selectedPositions.size());
					for (int position : selectedPositions) {
						Cursor c = (Cursor) adapter.getItem(position);
						games.add(new Pair<>(c.getInt(Query.GAME_ID), c.getString(Query.GAME_NAME)));
					}
					ActivityUtils.shareGames(getActivity(), games);
				}
				return true;
			case R.id.menu_link:
				mode.finish();
				ActivityUtils.linkBgg(getActivity(), gameId);
				return true;
		}
		return false;
	}
}
