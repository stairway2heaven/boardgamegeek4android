package com.boardgamegeek.util;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import android.text.TextUtils;

public class CollectionUrlBuilder extends UrlBuilder {
	private final String mUsername;
	private List<Integer> mGameIds;
	private String mStatus;
	private long mModifiedSince;
	private boolean mBrief;
	private boolean mShowPrivate;
	private boolean mStats;

	public CollectionUrlBuilder(String username) {
		mUsername = username;
		mGameIds = new ArrayList<Integer>();
	}

	public CollectionUrlBuilder gameId(int gameId) {
		mGameIds.clear();
		mGameIds.add(gameId);
		return this;
	}

	public CollectionUrlBuilder addGameId(int gameId) {
		mGameIds.add(gameId);
		return this;
	}

	public CollectionUrlBuilder status(String status) {
		mStatus = status;
		return this;
	}

	public CollectionUrlBuilder modifiedSince(long modifiedSince) {
		mModifiedSince = modifiedSince;
		return this;
	}

	public CollectionUrlBuilder brief() {
		mBrief = true;
		return this;
	}

	public CollectionUrlBuilder showPrivate() {
		mShowPrivate = true;
		return this;
	}

	public CollectionUrlBuilder stats() {
		mStats = true;
		return this;
	}

	public String build() {
		String url = BASE_URL_2 + "collection?username=" + encode(mUsername);
		if (mGameIds != null && mGameIds.size() > 0) {
			url += "&id=";
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < mGameIds.size(); i++) {
				sb.append(mGameIds.get(i));
				if (i < mGameIds.size() - 1) {
					sb.append(",");
				}
			}
			url += sb.toString();
		}
		if (!TextUtils.isEmpty(mStatus)) {
			url += "&" + mStatus.trim() + "=1";
		}
		if (mModifiedSince > 0) {
			url += "&modifiedsince=" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(mModifiedSince));
		}
		return url + (mShowPrivate ? "&showprivate=1" : "") + (mStats ? "&stats=1" : "") + (mBrief ? "&brief=1" : "");
	}
}
