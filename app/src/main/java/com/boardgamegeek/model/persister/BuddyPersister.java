package com.boardgamegeek.model.persister;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.boardgamegeek.model.Buddy;
import com.boardgamegeek.model.User;
import com.boardgamegeek.provider.BggContract;
import com.boardgamegeek.provider.BggContract.Avatars;
import com.boardgamegeek.provider.BggContract.Buddies;
import com.boardgamegeek.util.FileUtils;
import com.boardgamegeek.util.ResolverUtils;

import java.util.ArrayList;
import java.util.List;

public class BuddyPersister {
	private final Context context;
	private final long updateTime;

	public BuddyPersister(Context context) {
		this.context = context;
		updateTime = System.currentTimeMillis();
	}

	public long getTimestamp() {
		return updateTime;
	}

	public int save(User buddy) {
		List<User> buddies = new ArrayList<>(1);
		buddies.add(buddy);
		return save(buddies);
	}

	public int save(List<User> buddies) {
		ContentResolver resolver = context.getContentResolver();
		ArrayList<ContentProviderOperation> batch = new ArrayList<>();
		StringBuilder debugMessage = new StringBuilder();
		if (buddies != null) {
			for (User buddy : buddies) {
				if (!TextUtils.isEmpty(buddy.name)) {
					Uri uri = Buddies.buildBuddyUri(buddy.name);
					ContentValues values = new ContentValues();
					values.put(Buddies.UPDATED, updateTime);
					int oldSyncHashCode = ResolverUtils.queryInt(resolver, uri, Buddies.SYNC_HASH_CODE);
					int newSyncHashCode = generateSyncHashCode(buddy);
					if (oldSyncHashCode != newSyncHashCode) {
						values.put(Buddies.BUDDY_ID, buddy.getId());
						values.put(Buddies.BUDDY_NAME, buddy.name);
						values.put(Buddies.BUDDY_FIRSTNAME, buddy.firstName);
						values.put(Buddies.BUDDY_LASTNAME, buddy.lastName);
						values.put(Buddies.AVATAR_URL, buddy.avatarUrl);
						values.put(Buddies.SYNC_HASH_CODE, newSyncHashCode);
					}
					debugMessage.append("Saving ").append(uri).append("; ");
					addToBatch(resolver, values, batch, uri, debugMessage);
				}
			}
		}
		ContentProviderResult[] result = ResolverUtils.applyBatch(context, batch, debugMessage.toString());
		if (result == null) {
			return 0;
		} else {
			return result.length;
		}
	}

	public int saveList(Buddy buddy) {
		List<Buddy> buddies = new ArrayList<>(1);
		buddies.add(buddy);
		return saveList(buddies);
	}

	public int saveList(List<Buddy> buddies) {
		ContentResolver resolver = context.getContentResolver();
		ArrayList<ContentProviderOperation> batch = new ArrayList<>();
		StringBuilder debugMessage = new StringBuilder();
		if (buddies != null) {
			for (Buddy buddy : buddies) {
				if (buddy.getId() != BggContract.INVALID_ID) {
					ContentValues values = toValues(buddy);
					addToBatch(resolver, values, batch, Buddies.buildBuddyUri(buddy.name), debugMessage);
				}
			}
		}
		ContentProviderResult[] result = ResolverUtils.applyBatch(context, batch, debugMessage.toString());
		if (result == null) {
			return 0;
		} else {
			return result.length;
		}
	}

	private void addToBatch(ContentResolver resolver, ContentValues values, ArrayList<ContentProviderOperation> batch, Uri uri, StringBuilder debugMessage) {
		if (!ResolverUtils.rowExists(resolver, uri)) {
			debugMessage.append("Inserting ").append(uri).append("; ");
			values.put(Buddies.UPDATED_LIST, updateTime);
			batch.add(ContentProviderOperation.newInsert(Buddies.CONTENT_URI).withValues(values).build());
		} else {
			debugMessage.append("Updating ").append(uri).append("; ");
			maybeDeleteAvatar(values, uri, resolver);
			values.remove(Buddies.BUDDY_NAME);
			batch.add(ContentProviderOperation.newUpdate(uri).withValues(values).build());
		}
	}

	private ContentValues toValues(Buddy buddy) {
		ContentValues values = new ContentValues();
		values.put(Buddies.BUDDY_ID, buddy.getId());
		values.put(Buddies.BUDDY_NAME, buddy.name);
		values.put(Buddies.UPDATED_LIST, updateTime);
		values.put(Buddies.BUDDY_FLAG, 1);
		return values;
	}

	private static int generateSyncHashCode(User buddy) {
		return (buddy.firstName + "\n" + buddy.lastName + "\n" + buddy.avatarUrl + "\n").hashCode();
	}

	private static void maybeDeleteAvatar(ContentValues values, Uri uri, ContentResolver resolver) {
		if (!values.containsKey(Buddies.AVATAR_URL)) {
			// nothing to do - no avatar
			return;
		}

		String newAvatarUrl = values.getAsString(Buddies.AVATAR_URL);
		if (newAvatarUrl == null) {
			newAvatarUrl = "";
		}

		String oldAvatarUrl = ResolverUtils.queryString(resolver, uri, Buddies.AVATAR_URL);
		if (newAvatarUrl.equals(oldAvatarUrl)) {
			// nothing to do - avatar hasn't changed
			return;
		}

		String avatarFileName = FileUtils.getFileNameFromUrl(oldAvatarUrl);
		if (!TextUtils.isEmpty(avatarFileName)) {
			// TODO: use batch
			resolver.delete(Avatars.buildUri(avatarFileName), null, null);
		}
	}
}
