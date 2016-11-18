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
package com.android.contacts;

import android.app.Dialog;
import android.app.DialogFragment;
import android.app.LoaderManager;
import android.content.AsyncTaskLoader;
import android.content.Context;
import android.content.Loader;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v4.util.ArrayMap;
import android.support.v4.view.ViewCompat;
import android.support.v4.widget.ContentLoadingProgressBar;
import android.support.v7.widget.Toolbar;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.android.contacts.common.compat.CompatUtils;
import com.android.contacts.common.database.SimContactDao;
import com.android.contacts.common.model.AccountTypeManager;
import com.android.contacts.common.model.SimCard;
import com.android.contacts.common.model.SimContact;
import com.android.contacts.common.model.account.AccountWithDataSet;
import com.android.contacts.common.preference.ContactsPreferences;
import com.android.contacts.editor.AccountHeaderPresenter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dialog that presents a list of contacts from a SIM card that can be imported into a selected
 * account
 */
public class SimImportFragment extends DialogFragment
        implements LoaderManager.LoaderCallbacks<SimImportFragment.LoaderResult>,
        AdapterView.OnItemClickListener, AbsListView.OnScrollListener {

    private static final String KEY_SUFFIX_SELECTED_IDS = "_selectedIds";
    private static final String ARG_SUBSCRIPTION_ID = "subscriptionId";
    private static final String TAG = "SimImportFragment";

    private ContactsPreferences mPreferences;
    private AccountTypeManager mAccountTypeManager;
    private SimContactAdapter mAdapter;
    private View mAccountHeaderContainer;
    private AccountHeaderPresenter mAccountHeaderPresenter;
    private float mAccountScrolledElevationPixels;
    private ContentLoadingProgressBar mLoadingIndicator;
    private Toolbar mToolbar;
    private ListView mListView;
    private View mImportButton;

    private final Map<AccountWithDataSet, long[]> mPerAccountCheckedIds = new ArrayMap<>();

    private int mSubscriptionId;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setStyle(STYLE_NORMAL, R.style.PeopleThemeAppCompat_FullScreenDialog);
        mPreferences = new ContactsPreferences(getContext());
        mAccountTypeManager = AccountTypeManager.getInstance(getActivity());
        mAdapter = new SimContactAdapter(getActivity());

        final Bundle args = getArguments();
        mSubscriptionId = args == null ? SimCard.NO_SUBSCRIPTION_ID :
                args.getInt(ARG_SUBSCRIPTION_ID, SimCard.NO_SUBSCRIPTION_ID);
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        // Set the title for accessibility. It isn't displayed but will get announced when the
        // window is shown
        dialog.setTitle(R.string.sim_import_dialog_title);
        return dialog;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        getLoaderManager().initLoader(0, null, this);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_sim_import, container, false);

        mAccountHeaderContainer = view.findViewById(R.id.account_header_container);
        mAccountScrolledElevationPixels = getResources()
                .getDimension(R.dimen.contact_list_header_elevation);
        mAccountHeaderPresenter = new AccountHeaderPresenter(
                mAccountHeaderContainer);
        if (savedInstanceState != null) {
            mAccountHeaderPresenter.onRestoreInstanceState(savedInstanceState);
        } else {
            final AccountWithDataSet currentDefaultAccount = AccountWithDataSet
                    .getDefaultOrBestFallback(mPreferences, mAccountTypeManager);
            mAccountHeaderPresenter.setCurrentAccount(currentDefaultAccount);
        }
        mAccountHeaderPresenter.setObserver(new AccountHeaderPresenter.Observer() {
            @Override
            public void onChange(AccountHeaderPresenter sender) {
                rememberSelectionsForCurrentAccount();
                mAdapter.setAccount(sender.getCurrentAccount());
                showSelectionsForCurrentAccount();
                updateToolbarWithCurrentSelections();
            }
        });
        mAdapter.setAccount(mAccountHeaderPresenter.getCurrentAccount());
        restoreAdapterSelectedStates(savedInstanceState);

        mListView = (ListView) view.findViewById(R.id.list);
        mListView.setOnScrollListener(this);
        mListView.setAdapter(mAdapter);
        mListView.setChoiceMode(AbsListView.CHOICE_MODE_MULTIPLE);
        mListView.setOnItemClickListener(this);
        mImportButton = view.findViewById(R.id.import_button);
        mImportButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importCurrentSelections();
                // Do we wait for import to finish?
                dismiss();
            }
        });

        mToolbar = (Toolbar) view.findViewById(R.id.toolbar);
        mToolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });

        mLoadingIndicator = (ContentLoadingProgressBar) view.findViewById(R.id.loading_progress);

        return view;
    }

    private void rememberSelectionsForCurrentAccount() {
        final AccountWithDataSet current = mAdapter.getAccount();
        final long[] ids = mListView.getCheckedItemIds();
        Arrays.sort(ids);
        mPerAccountCheckedIds.put(current, ids);
    }

    private void showSelectionsForCurrentAccount() {
        final long[] ids = mPerAccountCheckedIds.get(mAdapter.getAccount());
        if (ids == null) {
            selectAll();
            return;
        }
        for (int i = 0, len = mListView.getCount(); i < len; i++) {
            mListView.setItemChecked(i,
                    Arrays.binarySearch(ids, mListView.getItemIdAtPosition(i)) >= 0);
        }
    }

    private void selectAll() {
        for (int i = 0, len = mListView.getCount(); i < len; i++) {
            mListView.setItemChecked(i, true);
        }
    }

    private void updateToolbarWithCurrentSelections() {
        // The ListView keeps checked state for items that are disabled but we only want  to
        // consider items that don't exist in the current account when updating the toolbar
        int importableCount = 0;
        final SparseBooleanArray checked = mListView.getCheckedItemPositions();
        for (int i = 0; i < checked.size(); i++) {
            if (checked.valueAt(i) && !mAdapter.existsInCurrentAccount(i)) {
                importableCount++;
            }
        }

        if (importableCount == 0) {
            mImportButton.setVisibility(View.GONE);
            mToolbar.setTitle(R.string.sim_import_title_none_selected);
        } else {
            mToolbar.setTitle(String.valueOf(importableCount));
            mImportButton.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mAdapter.isEmpty() && getLoaderManager().getLoader(0).isStarted()) {
            mLoadingIndicator.show();
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mAccountHeaderPresenter.onSaveInstanceState(outState);
        rememberSelectionsForCurrentAccount();
        saveAdapterSelectedStates(outState);
    }

    @Override
    public SimContactLoader onCreateLoader(int id, Bundle args) {
        return new SimContactLoader(getContext(), mSubscriptionId);
    }

    @Override
    public void onLoadFinished(Loader<LoaderResult> loader,
            LoaderResult data) {
        mLoadingIndicator.hide();
        if (data == null) {
            return;
        }
        mAdapter.setData(data);
        mListView.setEmptyView(getView().findViewById(R.id.empty_message));

        showSelectionsForCurrentAccount();
        updateToolbarWithCurrentSelections();
    }

    @Override
    public void onLoaderReset(Loader<LoaderResult> loader) {
    }

    private void restoreAdapterSelectedStates(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }

        final List<AccountWithDataSet> accounts = mAccountTypeManager.getAccounts(true);
        for (AccountWithDataSet account : accounts) {
            final long[] selections = savedInstanceState.getLongArray(
                    account.stringify() + KEY_SUFFIX_SELECTED_IDS);
            mPerAccountCheckedIds.put(account, selections);
        }
    }

    private void saveAdapterSelectedStates(Bundle outState) {
        if (mAdapter == null) {
            return;
        }

        // Make sure the selections are up-to-date
        for (Map.Entry<AccountWithDataSet, long[]> entry : mPerAccountCheckedIds.entrySet()) {
            outState.putLongArray(entry.getKey().stringify() + KEY_SUFFIX_SELECTED_IDS,
                    entry.getValue());
        }
    }

    private void importCurrentSelections() {
        final SparseBooleanArray checked = mListView.getCheckedItemPositions();
        final ArrayList<SimContact> importableContacts = new ArrayList<>(checked.size());
        for (int i = 0; i < checked.size(); i++) {
            // It's possible for existing contacts to be "checked" but we only want to import the
            // ones that don't already exist
            if (checked.valueAt(i) && !mAdapter.existsInCurrentAccount(i)) {
                importableContacts.add(mAdapter.getItem(i));
            }
        }
        ContactSaveService.startService(getContext(), ContactSaveService
                .createImportFromSimIntent(getContext(), mSubscriptionId,
                        importableContacts,
                        mAccountHeaderPresenter.getCurrentAccount()));
    }

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (mAdapter.existsInCurrentAccount(position)) {
            Snackbar.make(getView(), R.string.sim_import_contact_exists_toast,
                    Snackbar.LENGTH_LONG).show();
        } else {
            updateToolbarWithCurrentSelections();
        }
    }

    public Context getContext() {
        if (CompatUtils.isMarshmallowCompatible()) {
            return super.getContext();
        }
        return getActivity();
    }

    @Override
    public void onScrollStateChanged(AbsListView view, int scrollState) { }

    @Override
    public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
            int totalItemCount) {
        int firstCompletelyVisibleItem = firstVisibleItem;
        if (view != null && view.getChildAt(0) != null && view.getChildAt(0).getTop() < 0) {
            firstCompletelyVisibleItem++;
        }

        if (firstCompletelyVisibleItem == 0) {
            ViewCompat.setElevation(mAccountHeaderContainer, 0);
        } else {
            ViewCompat.setElevation(mAccountHeaderContainer, mAccountScrolledElevationPixels);
        }
    }

    /**
     * Creates a fragment that will display contacts stored on the default SIM card
     */
    public static SimImportFragment newInstance() {
        return new SimImportFragment();
    }

    /**
     * Creates a fragment that will display the contacts stored on the SIM card that has the
     * provided subscriptionId
     */
    public static SimImportFragment newInstance(int subscriptionId) {
        final SimImportFragment fragment = new SimImportFragment();
        final Bundle args = new Bundle();
        args.putInt(ARG_SUBSCRIPTION_ID, subscriptionId);
        fragment.setArguments(args);
        return fragment;
    }

    private static class SimContactAdapter extends ArrayAdapter<SimContact> {
        private Map<AccountWithDataSet, Set<SimContact>> mExistingMap;
        private AccountWithDataSet mSelectedAccount;
        private LayoutInflater mInflater;

        public SimContactAdapter(Context context) {
            super(context, 0);
            mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).getId();
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return !existsInCurrentAccount(position) ? 0 : 1;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView text = (TextView) convertView;
            if (text == null) {
                final int layoutRes = existsInCurrentAccount(position) ?
                        R.layout.sim_import_list_item_disabled :
                        R.layout.sim_import_list_item;
                text = (TextView) mInflater.inflate(layoutRes, parent, false);
            }
            text.setText(getItemLabel(getItem(position)));

            return text;
        }

        public void setData(LoaderResult result) {
            clear();
            addAll(result.contacts);
            mExistingMap = result.accountsMap;
        }

        public void setAccount(AccountWithDataSet account) {
            mSelectedAccount = account;
            notifyDataSetChanged();
        }

        public AccountWithDataSet getAccount() {
            return mSelectedAccount;
        }

        public boolean existsInCurrentAccount(int position) {
            return existsInCurrentAccount(getItem(position));
        }

        public boolean existsInCurrentAccount(SimContact contact) {
            if (mSelectedAccount == null || !mExistingMap.containsKey(mSelectedAccount)) {
                return false;
            }
            return mExistingMap.get(mSelectedAccount).contains(contact);
        }

        private String getItemLabel(SimContact contact) {
            if (contact.hasName()) {
                return contact.getName();
            } else if (contact.hasPhone()) {
                return contact.getPhone();
            } else if (contact.hasEmails()) {
                return contact.getEmails()[0];
            } else {
                // This isn't really possible because we skip empty SIM contacts during loading
                return "";
            }
        }
    }


    private static class SimContactLoader extends AsyncTaskLoader<LoaderResult> {
        private SimContactDao mDao;
        private final int mSubscriptionId;
        LoaderResult mResult;

        public SimContactLoader(Context context, int subscriptionId) {
            super(context);
            mDao = SimContactDao.create(context);
            mSubscriptionId = subscriptionId;
        }

        @Override
        protected void onStartLoading() {
            if (mResult != null) {
                deliverResult(mResult);
            } else {
                forceLoad();
            }
        }

        @Override
        public void deliverResult(LoaderResult result) {
            mResult = result;
            super.deliverResult(result);
        }

        @Override
        public LoaderResult loadInBackground() {
            final SimCard sim = mDao.getSimBySubscriptionId(mSubscriptionId);
            LoaderResult result = new LoaderResult();
            if (sim == null) {
                result.contacts = new ArrayList<>();
                result.accountsMap = Collections.emptyMap();
                return result;
            }
            result.contacts = mDao.loadContactsForSim(sim);
            result.accountsMap = mDao.findAccountsOfExistingSimContacts(result.contacts);
            return result;
        }

        @Override
        protected void onReset() {
            mResult = null;
        }

    }

    public static class LoaderResult {
        public ArrayList<SimContact> contacts;
        public Map<AccountWithDataSet, Set<SimContact>> accountsMap;
    }
}
