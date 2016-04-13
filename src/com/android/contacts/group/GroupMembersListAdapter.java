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

import android.content.Context;
import android.content.CursorLoader;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.Directory;
import android.view.View;
import android.view.ViewGroup;

import com.android.contacts.common.ContactPhotoManager.DefaultImageRequest;
import com.android.contacts.common.list.ContactEntryListAdapter;
import com.android.contacts.common.list.ContactListItemView;
import com.android.contacts.common.preference.ContactsPreferences;

/** Group members cursor adapter. */
public class GroupMembersListAdapter extends ContactEntryListAdapter {

    private static class GroupMembersQuery {

        private static final String[] PROJECTION_PRIMARY = new String[] {
                Data.CONTACT_ID,
                Data.PHOTO_ID,
                Data.LOOKUP_KEY,
                Data.CONTACT_PRESENCE,
                Data.CONTACT_STATUS,
                Data.DISPLAY_NAME_PRIMARY,
        };

        private static final String[] PROJECTION_ALTERNATIVE = new String[] {
                Data.CONTACT_ID,
                Data.PHOTO_ID,
                Data.LOOKUP_KEY,
                Data.CONTACT_PRESENCE,
                Data.CONTACT_STATUS,
                Data.DISPLAY_NAME_ALTERNATIVE,
        };

        public static final int CONTACT_ID                   = 0;
        public static final int CONTACT_PHOTO_ID             = 1;
        public static final int CONTACT_LOOKUP_KEY           = 2;
        public static final int CONTACT_PRESENCE             = 3;
        public static final int CONTACT_STATUS               = 4;
        public static final int CONTACT_DISPLAY_NAME         = 5;
    }

    private final CharSequence mUnknownNameText;
    private long mGroupId;

    public GroupMembersListAdapter(Context context) {
        super(context);
        mUnknownNameText = context.getText(android.R.string.unknownName);
        setIndexedPartition(0);
    }

    /** Sets the ID of the group whose members will be displayed. */
    public void setGroupId(long groupId) {
        mGroupId = groupId;
    }

    /** Returns the lookup Uri for the contact at the given position in the underlying cursor. */
    public Uri getContactLookupUri(int position) {
        final Cursor cursor = (Cursor) getItem(position);
        final long contactId = cursor.getLong(GroupMembersQuery.CONTACT_ID);
        final String lookupKey = cursor.getString(GroupMembersQuery.CONTACT_LOOKUP_KEY);
        return Contacts.getLookupUri(contactId, lookupKey);
    }

    @Override
    public void configureLoader(CursorLoader loader, long directoryId) {
        loader.setUri(Data.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.DIRECTORY_PARAM_KEY,
                        String.valueOf(Directory.DEFAULT))
                .appendQueryParameter(Contacts.EXTRA_ADDRESS_BOOK_INDEX, "true")
                .appendQueryParameter(Contacts.EXTRA_ADDRESS_BOOK_INDEX_COUNTS, "true")
                .build());

        loader.setSelection(Data.MIMETYPE + "=?" + " AND " + GroupMembership.GROUP_ROW_ID + "=?");

        final String[] selectionArgs = new String[2];
        selectionArgs[0] = GroupMembership.CONTENT_ITEM_TYPE;
        selectionArgs[1] = String.valueOf(mGroupId);
        loader.setSelectionArgs(selectionArgs);

        loader.setProjection(
                getContactNameDisplayOrder() == ContactsPreferences.DISPLAY_ORDER_PRIMARY
                        ? GroupMembersQuery.PROJECTION_PRIMARY
                        : GroupMembersQuery.PROJECTION_ALTERNATIVE);

        loader.setSortOrder(
                getSortOrder() == ContactsPreferences.SORT_ORDER_PRIMARY
                        ? Contacts.SORT_KEY_PRIMARY : Contacts.SORT_KEY_ALTERNATIVE);
    }

    @Override
    public String getContactDisplayName(int position) {
        return ((Cursor) getItem(position)).getString(GroupMembersQuery.CONTACT_DISPLAY_NAME);
    }

    @Override
    protected ContactListItemView newView(Context context, int partition, Cursor cursor,
            int position, ViewGroup parent) {
        final ContactListItemView view =
                super.newView(context, partition, cursor, position, parent);
        view.setUnknownNameText(mUnknownNameText);
        view.setQuickContactEnabled(isQuickContactEnabled());
        view.setIsSectionHeaderEnabled(isSectionHeaderDisplayEnabled());
        return view;
    }

    @Override
    protected void bindView(View v, int partition, Cursor cursor, int position) {
        super.bindView(v, partition, cursor, position);
        final ContactListItemView view = (ContactListItemView) v;
        bindSectionHeaderAndDivider(view, position);
        bindName(view, cursor);
        bindViewId(view, cursor, GroupMembersQuery.CONTACT_ID);
        bindPhoto(view, cursor);
    }

    private void bindSectionHeaderAndDivider(final ContactListItemView view, int position) {
        final int section = getSectionForPosition(position);
        if (getPositionForSection(section) == position) {
            final String header = (String) getSections()[section];
            view.setSectionHeader(header);
        } else {
            view.setSectionHeader(null);
        }
    }

    private void bindName(ContactListItemView view, Cursor cursor) {
        view.showDisplayName(cursor, GroupMembersQuery.CONTACT_DISPLAY_NAME,
                getContactNameDisplayOrder());
    }

    private void bindPhoto(final ContactListItemView view, Cursor cursor) {
        final long photoId = cursor.isNull(GroupMembersQuery.CONTACT_PHOTO_ID)
                ? 0 : cursor.getLong(GroupMembersQuery.CONTACT_PHOTO_ID);
        final DefaultImageRequest imageRequest = photoId == 0
                ? getDefaultImageRequestFromCursor(cursor, GroupMembersQuery.CONTACT_DISPLAY_NAME,
                        GroupMembersQuery.CONTACT_LOOKUP_KEY)
                : null;
        getPhotoLoader().loadThumbnail(view.getPhotoView(), photoId, false, getCircularPhotos(),
                imageRequest);
    }
}
