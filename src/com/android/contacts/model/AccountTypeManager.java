/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.contacts.model;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.OnAccountsUpdateListener;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SyncStatusObserver;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.util.Log;

import com.android.contacts.Experiments;
import com.android.contacts.R;
import com.android.contacts.list.ContactListFilterController;
import com.android.contacts.model.account.AccountComparator;
import com.android.contacts.model.account.AccountType;
import com.android.contacts.model.account.AccountTypeProvider;
import com.android.contacts.model.account.AccountTypeWithDataSet;
import com.android.contacts.model.account.AccountWithDataSet;
import com.android.contacts.model.account.FallbackAccountType;
import com.android.contacts.model.account.GoogleAccountType;
import com.android.contacts.model.dataitem.DataKind;
import com.android.contacts.util.concurrent.ContactsExecutors;
import com.android.contactsbind.experiments.Flags;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

/**
 * Singleton holder for all parsed {@link AccountType} available on the
 * system, typically filled through {@link PackageManager} queries.
 */
public abstract class AccountTypeManager {
    static final String TAG = "AccountTypeManager";

    private static final Object mInitializationLock = new Object();
    private static AccountTypeManager mAccountTypeManager;

    /**
     * Requests the singleton instance of {@link AccountTypeManager} with data bound from
     * the available authenticators. This method can safely be called from the UI thread.
     */
    public static AccountTypeManager getInstance(Context context) {
        if (!hasRequiredPermissions(context)) {
            // Hopefully any component that depends on the values returned by this class
            // will be restarted if the permissions change.
            return EMPTY;
        }
        synchronized (mInitializationLock) {
            if (mAccountTypeManager == null) {
                context = context.getApplicationContext();
                mAccountTypeManager = new AccountTypeManagerImpl(context);
            }
        }
        return mAccountTypeManager;
    }

    /**
     * Set the instance of account type manager.  This is only for and should only be used by unit
     * tests.  While having this method is not ideal, it's simpler than the alternative of
     * holding this as a service in the ContactsApplication context class.
     *
     * @param mockManager The mock AccountTypeManager.
     */
    public static void setInstanceForTest(AccountTypeManager mockManager) {
        synchronized (mInitializationLock) {
            mAccountTypeManager = mockManager;
        }
    }

    private static final AccountTypeManager EMPTY = new AccountTypeManager() {

        @Override
        public List<AccountWithDataSet> getAccounts(boolean contactWritableOnly) {
            return Collections.emptyList();
        }

        @Override
        public List<AccountWithDataSet> getAccounts(Predicate<AccountWithDataSet> filter) {
            return Collections.emptyList();
        }

        @Override
        public ListenableFuture<List<AccountWithDataSet>> getAllAccountsAsync() {
            return Futures.immediateFuture(Collections.<AccountWithDataSet>emptyList());
        }

        @Override
        public List<AccountWithDataSet> getGroupWritableAccounts() {
            return Collections.emptyList();
        }

        @Override
        public Account getDefaultGoogleAccount() {
            return null;
        }

        @Override
        public AccountType getAccountType(AccountTypeWithDataSet accountTypeWithDataSet) {
            return null;
        }
    };

    /**
     * Returns the list of all accounts (if contactWritableOnly is false) or just the list of
     * contact writable accounts (if contactWritableOnly is true).
     */
    // TODO: Consider splitting this into getContactWritableAccounts() and getAllAccounts()
    public abstract List<AccountWithDataSet> getAccounts(boolean contactWritableOnly);

    public abstract List<AccountWithDataSet> getAccounts(Predicate<AccountWithDataSet> filter);

    /**
     * Loads accounts in background and returns future that will complete with list of all accounts
     */
    public abstract ListenableFuture<List<AccountWithDataSet>> getAllAccountsAsync();

    /**
     * Returns the list of accounts that are group writable.
     */
    public abstract List<AccountWithDataSet> getGroupWritableAccounts();

    /**
     * Returns the default google account.
     */
    public abstract Account getDefaultGoogleAccount();

    static Account getDefaultGoogleAccount(AccountManager accountManager,
            SharedPreferences prefs, String defaultAccountKey) {
        // Get all the google accounts on the device
        final Account[] accounts = accountManager.getAccountsByType(
                GoogleAccountType.ACCOUNT_TYPE);
        if (accounts == null || accounts.length == 0) {
            return null;
        }

        // Get the default account from preferences
        final String defaultAccount = prefs.getString(defaultAccountKey, null);
        final AccountWithDataSet accountWithDataSet = defaultAccount == null ? null :
                AccountWithDataSet.unstringify(defaultAccount);

        // Look for an account matching the one from preferences
        if (accountWithDataSet != null) {
            for (int i = 0; i < accounts.length; i++) {
                if (TextUtils.equals(accountWithDataSet.name, accounts[i].name)
                        && TextUtils.equals(accountWithDataSet.type, accounts[i].type)) {
                    return accounts[i];
                }
            }
        }

        // Just return the first one
        return accounts[0];
    }

    public abstract AccountType getAccountType(AccountTypeWithDataSet accountTypeWithDataSet);

    public final AccountType getAccountType(String accountType, String dataSet) {
        return getAccountType(AccountTypeWithDataSet.get(accountType, dataSet));
    }

    public final AccountType getAccountTypeForAccount(AccountWithDataSet account) {
        if (account != null) {
            return getAccountType(account.getAccountTypeWithDataSet());
        }
        return getAccountType(null, null);
    }

    /**
     * Find the best {@link DataKind} matching the requested
     * {@link AccountType#accountType}, {@link AccountType#dataSet}, and {@link DataKind#mimeType}.
     * If no direct match found, we try searching {@link FallbackAccountType}.
     */
    public DataKind getKindOrFallback(AccountType type, String mimeType) {
        return type == null ? null : type.getKindForMimetype(mimeType);
    }

    /**
     * @param contactWritableOnly if true, it only returns ones that support writing contacts.
     * @return true when this instance contains the given account.
     */
    public boolean contains(AccountWithDataSet account, boolean contactWritableOnly) {
        for (AccountWithDataSet account_2 : getAccounts(contactWritableOnly)) {
            if (account.equals(account_2)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasGoogleAccount() {
        return getDefaultGoogleAccount() != null;
    }

    /**
     * Sorts the accounts in-place such that defaultAccount is first in the list and the rest
     * of the accounts are ordered in manner that is useful for display purposes
     */
    public static void sortAccounts(AccountWithDataSet defaultAccount,
            List<AccountWithDataSet> accounts) {
        Collections.sort(accounts, new AccountComparator(defaultAccount));
    }

    private static boolean hasRequiredPermissions(Context context) {
        final boolean canGetAccounts = ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED;
        final boolean canReadContacts = ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        return canGetAccounts && canReadContacts;
    }

    public static Predicate<AccountWithDataSet> nonNullAccountFilter() {
        return new Predicate<AccountWithDataSet>() {
            @Override
            public boolean apply(@Nullable AccountWithDataSet account) {
                return account != null && account.name != null && account.type != null;
            }
        };
    }

    public static Predicate<AccountType> writableFilter() {
        return new Predicate<AccountType>() {
            @Override
            public boolean apply(@Nullable AccountType account) {
                return account.areContactsWritable();
            }
        };
    }
}

class AccountTypeManagerImpl extends AccountTypeManager
        implements OnAccountsUpdateListener, SyncStatusObserver {

    private Context mContext;
    private AccountManager mAccountManager;
    private AccountTypeProvider mTypeCache;
    private ListeningExecutorService mExecutor;
    private Executor mMainThreadExecutor;

    private AccountType mFallbackAccountType;

    private ListenableFuture<AccountTypeProvider> mLoadingFuture;

    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reloadAccountTypes();
        }
    };

    /**
     * Internal constructor that only performs initial parsing.
     */
    public AccountTypeManagerImpl(Context context) {
        mContext = context;
        mTypeCache = new AccountTypeProvider(context);
        mFallbackAccountType = new FallbackAccountType(context);

        mAccountManager = AccountManager.get(mContext);

        mExecutor = ContactsExecutors.getDefaultThreadPoolExecutor();
        mMainThreadExecutor = ContactsExecutors.newHandlerExecutor(mMainThreadHandler);

        // Request updates when packages or accounts change
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        mContext.registerReceiver(mBroadcastReceiver, filter);
        IntentFilter sdFilter = new IntentFilter();
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        mContext.registerReceiver(mBroadcastReceiver, sdFilter);

        // Request updates when locale is changed so that the order of each field will
        // be able to be changed on the locale change.
        filter = new IntentFilter(Intent.ACTION_LOCALE_CHANGED);
        mContext.registerReceiver(mBroadcastReceiver, filter);

        mAccountManager.addOnAccountsUpdatedListener(this, mMainThreadHandler, false);

        ContentResolver.addStatusChangeListener(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS, this);

        if (Flags.getInstance().getBoolean(Experiments.OEM_CP2_DEVICE_ACCOUNT_DETECTION_ENABLED)) {
            // Observe changes to RAW_CONTACTS so that we will update the list of "Device" accounts
            // if a new device contact is added.
            mContext.getContentResolver().registerContentObserver(
                    ContactsContract.RawContacts.CONTENT_URI, /* notifyDescendents */ true,
                    new ContentObserver(mMainThreadHandler) {
                        @Override
                        public boolean deliverSelfNotifications() {
                            return true;
                        }

                        @Override
                        public void onChange(boolean selfChange) {
                            // TODO: update device local account types
                        }

                        @Override
                        public void onChange(boolean selfChange, Uri uri) {
                            // TODO: update device local account types
                        }
                    });
        }
    }

    @Override
    public void onStatusChanged(int which) {
        reloadAccountTypes();
    }

    /* This notification will arrive on the background thread */
    public void onAccountsUpdated(Account[] accounts) {
        onAccountsUpdatedInternal();
    }

    private void onAccountsUpdatedInternal() {
        ContactListFilterController.getInstance(mContext).checkFilterValidity(true);
    }

    private synchronized void startLoadingIfNeeded() {
        if (mTypeCache == null && mLoadingFuture == null) {
            reloadAccountTypes();
        }
    }

    private void reloadAccountTypes() {
        mTypeCache = new AccountTypeProvider(mContext);
        cacheAccountTypes();
    }

    private void cacheAccountTypes() {
        mLoadingFuture = mExecutor.submit(new Callable<AccountTypeProvider>() {
            @Override
            public AccountTypeProvider call() throws Exception {
                AccountTypeProvider cache = new AccountTypeProvider(mContext);
                getAllAccounts(cache);
                return cache;
            }
        });
        Futures.addCallback(mLoadingFuture, new FutureCallback<AccountTypeProvider>() {
            @Override
            public void onSuccess(AccountTypeProvider result) {
                mTypeCache = result;
            }

            @Override
            public void onFailure(Throwable t) {
            }
        }, mMainThreadExecutor);
    }

    /**
     * Return list of all known or contact writable {@link AccountWithDataSet}'s.
     * {@param contactWritableOnly} whether to restrict to contact writable accounts only
     */
    @Override
    public List<AccountWithDataSet> getAccounts(boolean contactWritableOnly) {
        return contactWritableOnly ? filterAccountsByType(writableFilter()) : getAllAccounts();
    }

    @Override
    public List<AccountWithDataSet> getAccounts(Predicate<AccountWithDataSet> filter) {
        return new ArrayList<>(Collections2.filter(getAllAccounts(), filter));
    }

    @Override
    public ListenableFuture<List<AccountWithDataSet>> getAllAccountsAsync() {
        startLoadingIfNeeded();
        return Futures.transform(mLoadingFuture,
                new Function<AccountTypeProvider, List<AccountWithDataSet>>() {
                    @Override
                    public List<AccountWithDataSet> apply(AccountTypeProvider input) {
                        return getAllAccounts(input);
                    }
                });
    }

    private List<AccountWithDataSet> getAllAccounts(AccountTypeProvider cache) {
        final List<AccountWithDataSet> result = new ArrayList<>();
        final Account[] accounts = mAccountManager.getAccounts();
        for (Account account : accounts) {
            final List<AccountType> types = cache.getAccountTypes(account.type);
            for (AccountType type : types) {
                result.add(new AccountWithDataSet(account.name, account.type, type.dataSet));
            }
        }
        return result;
    }

    private List<AccountWithDataSet> getAllAccounts() {
        return getAllAccounts(mTypeCache);
    }

    private List<AccountWithDataSet> filterAccountsByType(final Predicate<AccountType> typeFilter) {
        return getAccounts(new Predicate<AccountWithDataSet>() {
            @Override
            public boolean apply(@Nullable AccountWithDataSet input) {
                final AccountType type = mTypeCache.getTypeForAccount(input);
                return type != null && typeFilter.apply(type);
            }
        });
    }


    /**
     * Return the list of all known, group writable {@link AccountWithDataSet}'s.
     */
    public List<AccountWithDataSet> getGroupWritableAccounts() {
        return filterAccountsByType(new Predicate<AccountType>() {
            @Override
            public boolean apply(AccountType accountType) {
                return accountType.isGroupMembershipEditable();
            }
        });
    }

    /**
     * Returns the default google account specified in preferences, the first google account
     * if it is not specified in preferences or is no longer on the device, and null otherwise.
     */
    @Override
    public Account getDefaultGoogleAccount() {
        final AccountManager accountManager = AccountManager.get(mContext);
        final SharedPreferences sharedPreferences =
                mContext.getSharedPreferences(mContext.getPackageName(), Context.MODE_PRIVATE);
        final String defaultAccountKey =
                mContext.getResources().getString(R.string.contact_editor_default_account_key);
        return getDefaultGoogleAccount(accountManager, sharedPreferences, defaultAccountKey);
    }

    /**
     * Find the best {@link DataKind} matching the requested
     * {@link AccountType#accountType}, {@link AccountType#dataSet}, and {@link DataKind#mimeType}.
     * If no direct match found, we try searching {@link FallbackAccountType}.
     */
    @Override
    public DataKind getKindOrFallback(AccountType type, String mimeType) {
        DataKind kind = null;

        // Try finding account type and kind matching request
        if (type != null) {
            kind = type.getKindForMimetype(mimeType);
        }

        if (kind == null) {
            // Nothing found, so try fallback as last resort
            kind = mFallbackAccountType.getKindForMimetype(mimeType);
        }

        if (kind == null) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Unknown type=" + type + ", mime=" + mimeType);
            }
        }

        return kind;
    }

    /**
     * Return {@link AccountType} for the given account type and data set.
     */
    @Override
    public AccountType getAccountType(AccountTypeWithDataSet accountTypeWithDataSet) {
        return mTypeCache.getType(
                accountTypeWithDataSet.accountType, accountTypeWithDataSet.dataSet);
    }
}
