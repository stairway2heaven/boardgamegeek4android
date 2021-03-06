package com.boardgamegeek.service;

import android.content.Context;
import android.support.annotation.NonNull;

public abstract class UpdateTask extends ServiceTask {
	public abstract void execute(Context context);

	@NonNull
	public abstract String getDescription(Context context);

	public abstract boolean isValid();
}
