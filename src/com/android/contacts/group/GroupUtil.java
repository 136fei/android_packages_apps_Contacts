/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.contacts.group;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract.Groups;

import com.android.contacts.GroupListLoader;
import com.android.contacts.activities.GroupDetailActivity;
import com.android.contacts.activities.GroupEditorActivity;
import com.google.common.base.Objects;

/**
 * Group utility methods.
 */
public final class GroupUtil {

    private GroupUtil() {
    }

    /** Returns a {@link GroupListItem} read from the given cursor and position. */
    static GroupListItem getGroupListItem(Cursor cursor, int position) {
        if (cursor == null || cursor.isClosed() || !cursor.moveToPosition(position)) {
            return null;
        }
        String accountName = cursor.getString(GroupListLoader.ACCOUNT_NAME);
        String accountType = cursor.getString(GroupListLoader.ACCOUNT_TYPE);
        String dataSet = cursor.getString(GroupListLoader.DATA_SET);
        long groupId = cursor.getLong(GroupListLoader.GROUP_ID);
        String title = cursor.getString(GroupListLoader.TITLE);
        int memberCount = cursor.getInt(GroupListLoader.MEMBER_COUNT);

        // Figure out if this is the first group for this account name / account type pair by
        // checking the previous entry. This is to determine whether or not we need to display an
        // account header in this item.
        int previousIndex = position - 1;
        boolean isFirstGroupInAccount = true;
        if (previousIndex >= 0 && cursor.moveToPosition(previousIndex)) {
            String previousGroupAccountName = cursor.getString(GroupListLoader.ACCOUNT_NAME);
            String previousGroupAccountType = cursor.getString(GroupListLoader.ACCOUNT_TYPE);
            String previousGroupDataSet = cursor.getString(GroupListLoader.DATA_SET);

            if (accountName.equals(previousGroupAccountName) &&
                    accountType.equals(previousGroupAccountType) &&
                    Objects.equal(dataSet, previousGroupDataSet)) {
                isFirstGroupInAccount = false;
            }
        }

        return new GroupListItem(accountName, accountType, dataSet, groupId, title,
                isFirstGroupInAccount, memberCount);
    }

    /** Returns an Intent to create a new group. */
    public static Intent createAddGroupIntent(Context context) {
        final Intent intent = new Intent(context, GroupEditorActivity.class);
        intent.setAction(Intent.ACTION_INSERT);
        return intent;
    }

    /** Returns an Intent to view the details of the group identified by the given Uri. */
    public static Intent createViewGroupIntent(Context context, long groupId) {
        final Intent intent = new Intent(context, GroupDetailActivity.class);
        intent.setData(getGroupUriFromId(groupId));
        return intent;
    }

    /** TODO: Make it private after {@link GroupBrowseListAdapter} is removed. */
    static Uri getGroupUriFromId(long groupId) {
        return ContentUris.withAppendedId(Groups.CONTENT_URI, groupId);
    }
}