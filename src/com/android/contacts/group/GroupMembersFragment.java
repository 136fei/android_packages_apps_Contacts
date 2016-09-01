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
 * limitations under the License
 */
package com.android.contacts.group;

import android.app.Activity;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract.Contacts;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.android.contacts.ContactSaveService;
import com.android.contacts.ContactsDrawerActivity;
import com.android.contacts.GroupMetaDataLoader;
import com.android.contacts.R;
import com.android.contacts.activities.ActionBarAdapter;
import com.android.contacts.common.list.ContactsSectionIndexer;
import com.android.contacts.common.list.MultiSelectEntryContactListAdapter.DeleteContactListener;
import com.android.contacts.common.logging.ListEvent;
import com.android.contacts.common.logging.ListEvent.ListType;
import com.android.contacts.common.logging.Logger;
import com.android.contacts.common.logging.ScreenEvent;
import com.android.contacts.common.model.AccountTypeManager;
import com.android.contacts.common.model.account.AccountType;
import com.android.contacts.common.model.account.AccountWithDataSet;
import com.android.contacts.common.util.ImplicitIntentsUtil;
import com.android.contacts.group.GroupMembersAdapter.GroupMembersQuery;
import com.android.contacts.interactions.GroupDeletionDialogFragment;
import com.android.contacts.list.ContactsRequest;
import com.android.contacts.list.MultiSelectContactsListFragment;
import com.android.contacts.list.UiIntentActions;
import com.android.contacts.quickcontact.QuickContactActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Displays the members of a group. */
public class GroupMembersFragment extends MultiSelectContactsListFragment<GroupMembersAdapter> {

    private static final String TAG = "GroupMembers";

    private static final String KEY_IS_EDIT_MODE = "editMode";
    private static final String KEY_GROUP_URI = "groupUri";
    private static final String KEY_GROUP_METADATA = "groupMetadata";

    public static final String TAG_GROUP_NAME_EDIT_DIALOG = "groupNameEditDialog";

    private static final String ARG_GROUP_URI = "groupUri";

    private static final int LOADER_GROUP_METADATA = 0;
    private static final int RESULT_GROUP_ADD_MEMBER = 100;

    /** Filters out duplicate contacts. */
    private class FilterCursorWrapper extends CursorWrapper {

        private int[] mIndex;
        private int mCount = 0;
        private int mPos = 0;

        public FilterCursorWrapper(Cursor cursor) {
            super(cursor);

            mCount = super.getCount();
            mIndex = new int[mCount];

            final List<Integer> indicesToFilter = new ArrayList<>();

            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Group members CursorWrapper start: " + mCount);
            }

            final Bundle bundle = cursor.getExtras();
            final String sections[] = bundle.getStringArray(Contacts
                    .EXTRA_ADDRESS_BOOK_INDEX_TITLES);
            final int counts[] = bundle.getIntArray(Contacts.EXTRA_ADDRESS_BOOK_INDEX_COUNTS);
            final ContactsSectionIndexer indexer = (sections == null || counts == null)
                    ? null : new ContactsSectionIndexer(sections, counts);

            mGroupMemberContactIds.clear();
            for (int i = 0; i < mCount; i++) {
                super.moveToPosition(i);
                final String contactId = getString(GroupMembersQuery.CONTACT_ID);
                if (!mGroupMemberContactIds.contains(contactId)) {
                    mIndex[mPos++] = i;
                    mGroupMemberContactIds.add(contactId);
                } else {
                    indicesToFilter.add(i);
                }
            }

            if (indexer != null && GroupUtil.needTrimming(mCount, counts, indexer.getPositions())) {
                GroupUtil.updateBundle(bundle, indexer, indicesToFilter, sections, counts);
            }

            mCount = mPos;
            mPos = 0;
            super.moveToFirst();

            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Group members CursorWrapper end: " + mCount);
            }
        }

        @Override
        public boolean move(int offset) {
            return moveToPosition(mPos + offset);
        }

        @Override
        public boolean moveToNext() {
            return moveToPosition(mPos + 1);
        }

        @Override
        public boolean moveToPrevious() {
            return moveToPosition(mPos - 1);
        }

        @Override
        public boolean moveToFirst() {
            return moveToPosition(0);
        }

        @Override
        public boolean moveToLast() {
            return moveToPosition(mCount - 1);
        }

        @Override
        public boolean moveToPosition(int position) {
            if (position >= mCount) {
                mPos = mCount;
                return false;
            } else if (position < 0) {
                mPos = -1;
                return false;
            }
            mPos = mIndex[position];
            return super.moveToPosition(mPos);
        }

        @Override
        public int getCount() {
            return mCount;
        }

        @Override
        public int getPosition() {
            return mPos;
        }
    }

    private final LoaderCallbacks<Cursor> mGroupMetadataCallbacks = new LoaderCallbacks<Cursor>() {

        @Override
        public CursorLoader onCreateLoader(int id, Bundle args) {
            return new GroupMetaDataLoader(mActivity, mGroupUri);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
            if (cursor == null || cursor.isClosed() || !cursor.moveToNext()) {
                Log.e(TAG, "Failed to load group metadata for " + mGroupUri);
                Toast.makeText(getContext(), R.string.groupLoadErrorToast, Toast.LENGTH_SHORT)
                        .show();
                mActivity.setResult(AppCompatActivity.RESULT_CANCELED);
                mActivity.finish();
                return;
            }
            mGroupMetadata = new GroupMetadata();
            mGroupMetadata.uri = mGroupUri;
            mGroupMetadata.accountName = cursor.getString(GroupMetaDataLoader.ACCOUNT_NAME);
            mGroupMetadata.accountType = cursor.getString(GroupMetaDataLoader.ACCOUNT_TYPE);
            mGroupMetadata.dataSet = cursor.getString(GroupMetaDataLoader.DATA_SET);
            mGroupMetadata.groupId = cursor.getLong(GroupMetaDataLoader.GROUP_ID);
            mGroupMetadata.groupName = cursor.getString(GroupMetaDataLoader.TITLE);
            mGroupMetadata.readOnly = cursor.getInt(GroupMetaDataLoader.IS_READ_ONLY) == 1;

            final AccountTypeManager accountTypeManager =
                    AccountTypeManager.getInstance(mActivity);
            final AccountType accountType = accountTypeManager.getAccountType(
                    mGroupMetadata.accountType, mGroupMetadata.dataSet);
            mGroupMetadata.editable = accountType.isGroupMembershipEditable();

            onGroupMetadataLoaded();
        }

        @Override
        public void onLoaderReset(Loader<Cursor> loader) {}
    };

    private ActionBarAdapter mActionBarAdapter;

    private ContactsDrawerActivity mActivity;

    private Uri mGroupUri;

    private boolean mIsEditMode;

    private GroupMetadata mGroupMetadata;

    private Set<String> mGroupMemberContactIds = new HashSet();

    public static GroupMembersFragment newInstance(Uri groupUri) {
        final Bundle args = new Bundle();
        args.putParcelable(ARG_GROUP_URI, groupUri);

        final GroupMembersFragment fragment = new GroupMembersFragment();
        fragment.setArguments(args);
        return fragment;
    }

    public GroupMembersFragment() {
        setPhotoLoaderEnabled(true);
        setSectionHeaderDisplayEnabled(true);
        setHasOptionsMenu(true);
        setListType(ListType.GROUP);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        if (mGroupMetadata == null) {
            // Hide menu options until metadata is fully loaded
            return;
        }
        inflater.inflate(R.menu.view_group, menu);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        final boolean isSelectionMode = mActionBarAdapter.isSelectionMode();
        final boolean isGroupEditable = mGroupMetadata != null && mGroupMetadata.editable;
        final boolean isGroupReadOnly = mGroupMetadata != null && mGroupMetadata.readOnly;

        setVisible(menu, R.id.menu_add, isGroupEditable && !isSelectionMode);
        setVisible(menu, R.id.menu_rename_group, !isGroupReadOnly && !isSelectionMode);
        setVisible(menu, R.id.menu_delete_group, !isGroupReadOnly && !isSelectionMode);
        setVisible(menu, R.id.menu_edit_group, isGroupEditable && !mIsEditMode && !isSelectionMode
                && !isGroupEmpty());
        setVisible(menu, R.id.menu_remove_from_group, isGroupEditable && isSelectionMode &&
                !mIsEditMode);
    }

    private boolean isGroupEmpty() {
        return getAdapter() != null && getAdapter().isEmpty();
    }

    private static void setVisible(Menu menu, int id, boolean visible) {
        final MenuItem menuItem = menu.findItem(id);
        if (menuItem != null) {
            menuItem.setVisible(visible);
        }
    }

    private void startGroupAddMemberActivity() {
        startActivityForResult(GroupUtil.createPickMemberIntent(getContext(), mGroupMetadata,
                getMemberContactIds()), RESULT_GROUP_ADD_MEMBER);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: {
                mActivity.onBackPressed();
                return true;
            }
            case R.id.menu_add: {
                startGroupAddMemberActivity();
                return true;
            }
            case R.id.menu_rename_group: {
                GroupNameEditDialogFragment.newInstanceForUpdate(
                        new AccountWithDataSet(mGroupMetadata.accountName,
                                mGroupMetadata.accountType, mGroupMetadata.dataSet),
                        GroupUtil.ACTION_UPDATE_GROUP, mGroupMetadata.groupId,
                        mGroupMetadata.groupName).show(getFragmentManager(),
                        TAG_GROUP_NAME_EDIT_DIALOG);
                return true;
            }
            case R.id.menu_delete_group: {
                deleteGroup();
                return true;
            }
            case R.id.menu_edit_group: {
                mIsEditMode = true;
                mActionBarAdapter.setSelectionMode(true);
                displayDeleteButtons(true);
                return true;
            }
            case R.id.menu_remove_from_group: {
                logListEvent();
                removeSelectedContacts();
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    private void removeSelectedContacts() {
        final long[] contactIds = getAdapter().getSelectedContactIdsArray();
        new UpdateGroupMembersAsyncTask(UpdateGroupMembersAsyncTask.TYPE_REMOVE,
                getContext(), contactIds, mGroupMetadata.groupId, mGroupMetadata.accountName,
                mGroupMetadata.accountType).execute();

        mActionBarAdapter.setSelectionMode(false);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == RESULT_GROUP_ADD_MEMBER && resultCode ==
                Activity.RESULT_OK && data != null) {
            long[] contactIds = data.getLongArrayExtra(
                    UiIntentActions.TARGET_CONTACT_IDS_EXTRA_KEY);
            if (contactIds == null) {
                final long contactId = data.getLongExtra(
                        UiIntentActions.TARGET_CONTACT_ID_EXTRA_KEY, -1);
                if (contactId > -1) {
                    contactIds = new long[1];
                    contactIds[0] = contactId;
                }
            }
            new UpdateGroupMembersAsyncTask(
                    UpdateGroupMembersAsyncTask.TYPE_ADD,
                    getContext(), contactIds, mGroupMetadata.groupId, mGroupMetadata.accountName,
                    mGroupMetadata.accountType).execute();
        }
    }

    private final ActionBarAdapter.Listener mActionBarListener = new ActionBarAdapter.Listener() {
        @Override
        public void onAction(int action) {
            switch (action) {
                case ActionBarAdapter.Listener.Action.START_SELECTION_MODE:
                    if (mIsEditMode) {
                        displayDeleteButtons(true);
                        mActionBarAdapter.setActionBarTitle(getString(R.string.title_edit_group));
                    } else {
                        displayCheckBoxes(true);
                    }
                    mActivity.invalidateOptionsMenu();
                    break;
                case ActionBarAdapter.Listener.Action.STOP_SEARCH_AND_SELECTION_MODE:
                    mActionBarAdapter.setSearchMode(false);
                    if (mIsEditMode) {
                        displayDeleteButtons(false);
                    } else {
                        displayCheckBoxes(false);
                    }
                    mActivity.invalidateOptionsMenu();
                    break;
                case ActionBarAdapter.Listener.Action.BEGIN_STOPPING_SEARCH_AND_SELECTION_MODE:
                    break;
            }
        }

        @Override
        public void onUpButtonPressed() {
            mActivity.onBackPressed();
        }
    };

    private final OnCheckBoxListActionListener mCheckBoxListener =
            new OnCheckBoxListActionListener() {
                @Override
                public void onStartDisplayingCheckBoxes() {
                    mActionBarAdapter.setSelectionMode(true);
                }

                @Override
                public void onSelectedContactIdsChanged() {
                    if (mActionBarAdapter == null) {
                        return;
                    }
                    if (mIsEditMode) {
                        mActionBarAdapter.setActionBarTitle(getString(R.string.title_edit_group));
                    } else {
                        mActionBarAdapter.setSelectionCount(getSelectedContactIds().size());
                    }
                }

                @Override
                public void onStopDisplayingCheckBoxes() {
                    mActionBarAdapter.setSelectionMode(false);
                }
            };

    private void logListEvent() {
        Logger.logListEvent(
                ListEvent.ActionType.REMOVE_LABEL,
                getListType(),
                getAdapter().getCount(),
                /* clickedIndex */ -1,
                getAdapter().getSelectedContactIdsArray().length);
    }

    private void deleteGroup() {
        if (getMemberCount() == 0) {
            final Intent intent = ContactSaveService.createGroupDeletionIntent(
                    getContext(), mGroupMetadata.groupId);
            getContext().startService(intent);
            mActivity.switchToAllContacts();
        } else {
            GroupDeletionDialogFragment.show(getFragmentManager(), mGroupMetadata.groupId,
                    mGroupMetadata.groupName);
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mActivity = (ContactsDrawerActivity) getActivity();
        mActionBarAdapter = new ActionBarAdapter(mActivity, mActionBarListener,
                mActivity.getSupportActionBar(), mActivity.getToolbar(), R.string.enter_contact_name);
        mActionBarAdapter.setShowHomeIcon(true);
        final ContactsRequest contactsRequest = new ContactsRequest();
        contactsRequest.setActionCode(ContactsRequest.ACTION_GROUP);
        mActionBarAdapter.initialize(savedInstanceState, contactsRequest);
        if (mGroupMetadata != null) {
            mActivity.setTitle(mGroupMetadata.groupName);
            if (mGroupMetadata.editable) {
                setCheckBoxListListener(mCheckBoxListener);
            }
        }
    }

    @Override
    public ActionBarAdapter getActionBarAdapter() {
        return mActionBarAdapter;
    }

    public void displayDeleteButtons(boolean displayDeleteButtons) {
        getAdapter().setDisplayDeleteButtons(displayDeleteButtons);
    }

    public ArrayList<String> getMemberContactIds() {
        return new ArrayList<>(mGroupMemberContactIds);
    }

    public int getMemberCount() {
        return mGroupMemberContactIds.size();
    }

    public boolean isEditMode() {
        return mIsEditMode;
    }

    public void setEditMode(boolean isEditMode) {
        mIsEditMode = isEditMode;
    }

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        if (savedState == null) {
            mGroupUri = getArguments().getParcelable(ARG_GROUP_URI);
        } else {
            mIsEditMode = savedState.getBoolean(KEY_IS_EDIT_MODE);
            mGroupUri = savedState.getParcelable(KEY_GROUP_URI);
            mGroupMetadata = savedState.getParcelable(KEY_GROUP_METADATA);
        }
        maybeAttachCheckBoxListener();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Re-register the listener, which may have been cleared when onSaveInstanceState was
        // called. See also: onSaveInstanceState
        mActionBarAdapter.setListener(mActionBarListener);
    }

    @Override
    protected void startLoading() {
        if (mGroupMetadata == null || !mGroupMetadata.isValid()) {
            getLoaderManager().restartLoader(LOADER_GROUP_METADATA, null, mGroupMetadataCallbacks);
        } else {
            onGroupMetadataLoaded();
        }
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        if (data != null) {
            // Wait until contacts are loaded before showing the scrollbar
            setVisibleScrollbarEnabled(true);

            final FilterCursorWrapper cursorWrapper = new FilterCursorWrapper(data);
            bindMembersCount(cursorWrapper.getCount());
            super.onLoadFinished(loader, cursorWrapper);
            // Update state of menu items (e.g. "Remove contacts") based on number of group members.
            mActivity.invalidateOptionsMenu();
        }
    }

    private void bindMembersCount(int memberCount) {
        final View accountFilterContainer = getView().findViewById(
                R.id.account_filter_header_container);
        final View emptyGroupView = getView().findViewById(R.id.empty_group);
        if (memberCount > 0) {
            final AccountWithDataSet accountWithDataSet = new AccountWithDataSet(
                    mGroupMetadata.accountName, mGroupMetadata.accountType, mGroupMetadata.dataSet);
            bindListHeader(getContext(), getListView(), accountFilterContainer,
                    accountWithDataSet, memberCount);
            emptyGroupView.setVisibility(View.GONE);
        } else {
            hideHeaderAndAddPadding(getContext(), getListView(), accountFilterContainer);
            emptyGroupView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mActionBarAdapter != null) {
            mActionBarAdapter.setListener(null);
            mActionBarAdapter.onSaveInstanceState(outState);
        }
        outState.putBoolean(KEY_IS_EDIT_MODE, mIsEditMode);
        outState.putParcelable(KEY_GROUP_URI, mGroupUri);
        outState.putParcelable(KEY_GROUP_METADATA, mGroupMetadata);
    }

    private void onGroupMetadataLoaded() {
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "Loaded " + mGroupMetadata);

        maybeAttachCheckBoxListener();

        mActivity.setTitle(mGroupMetadata.groupName);
        mActivity.updateGroupMenu(mGroupMetadata);
        mActivity.invalidateOptionsMenu();

        // Start loading the group members
        super.startLoading();
    }

    private void maybeAttachCheckBoxListener() {
        // Don't attach the multi select check box listener if we can't edit the group
        if (mGroupMetadata != null && mGroupMetadata.editable) {
            setCheckBoxListListener(mCheckBoxListener);
        }
    }

    @Override
    protected GroupMembersAdapter createListAdapter() {
        final GroupMembersAdapter adapter = new GroupMembersAdapter(getContext());
        adapter.setSectionHeaderDisplayEnabled(true);
        adapter.setDisplayPhotos(true);
        adapter.setDeleteContactListener(new DeletionListener());
        return adapter;
    }

    @Override
    protected void configureAdapter() {
        super.configureAdapter();
        if (mGroupMetadata != null) {
            getAdapter().setGroupId(mGroupMetadata.groupId);
        }
    }

    @Override
    protected View inflateView(LayoutInflater inflater, ViewGroup container) {
        final View view = inflater.inflate(R.layout.contact_list_content, /* root */ null);
        final View emptyGroupView = inflater.inflate(R.layout.empty_group_view, null);

        final ImageView image = (ImageView) emptyGroupView.findViewById(R.id.empty_group_image);
        final LinearLayout.LayoutParams params =
                (LinearLayout.LayoutParams) image.getLayoutParams();
        final int screenHeight = getResources().getDisplayMetrics().heightPixels;
        params.setMargins(0, screenHeight /
                getResources().getInteger(R.integer.empty_group_view_image_margin_divisor), 0, 0);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        image.setLayoutParams(params);

        final FrameLayout contactListLayout = (FrameLayout) view.findViewById(R.id.contact_list);
        contactListLayout.addView(emptyGroupView);

        final Button addContactsButton =
                (Button) emptyGroupView.findViewById(R.id.add_member_button);
        addContactsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(GroupUtil.createPickMemberIntent(getContext(),
                        mGroupMetadata, getMemberContactIds()), RESULT_GROUP_ADD_MEMBER);
            }
        });
        return view;
    }

    @Override
    protected void onItemClick(int position, long id) {
        final Uri uri = getAdapter().getContactUri(position);
        if (uri == null) {
            return;
        }
        if (getAdapter().isDisplayingCheckBoxes()) {
            super.onItemClick(position, id);
            return;
        }
        final int count = getAdapter().getCount();
        Logger.logListEvent(ListEvent.ActionType.CLICK, ListEvent.ListType.GROUP, count,
                /* clickedIndex */ position, /* numSelected */ 0);
        final Intent intent = ImplicitIntentsUtil.composeQuickContactIntent(
                getContext(), uri, QuickContactActivity.MODE_FULLY_EXPANDED);
        intent.putExtra(
                QuickContactActivity.EXTRA_PREVIOUS_SCREEN_TYPE, ScreenEvent.ScreenType.LIST_GROUP);
        startActivity(intent);
    }

    @Override
    protected boolean onItemLongClick(int position, long id) {
        if (mActivity != null && mIsEditMode) {
            return true;
        }
        return super.onItemLongClick(position, id);
    }

    private final class DeletionListener implements DeleteContactListener {
        @Override
        public void onContactDeleteClicked(int position) {
            final long contactId = getAdapter().getContactId(position);
            final long[] contactIds = new long[1];
            contactIds[0] = contactId;
            new UpdateGroupMembersAsyncTask(UpdateGroupMembersAsyncTask.TYPE_REMOVE,
                    getContext(), contactIds, mGroupMetadata.groupId, mGroupMetadata.accountName,
                    mGroupMetadata.accountType).execute();
        }
    }

    public GroupMetadata getGroupMetadata() {
        return mGroupMetadata;
    }

    public boolean isCurrentGroup(long groupId) {
        return mGroupMetadata != null && mGroupMetadata.groupId == groupId;
    }

    @Override
    public void onDestroy() {
        if (mActionBarAdapter != null) {
            mActionBarAdapter.setListener(null);
        }
        super.onDestroy();
    }
}
