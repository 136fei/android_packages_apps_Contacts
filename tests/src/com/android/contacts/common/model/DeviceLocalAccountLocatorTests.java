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
package com.android.contacts.common.model;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.ContactsContract;
import android.provider.ContactsContract.RawContacts;
import android.support.annotation.Nullable;
import android.test.AndroidTestCase;
import android.test.mock.MockContentResolver;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.contacts.common.model.account.AccountWithDataSet;
import com.android.contacts.common.test.mocks.MockContentProvider;
import com.android.contacts.common.util.DeviceLocalAccountTypeFactory;
import com.android.contacts.tests.FakeDeviceAccountTypeFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SmallTest
public class DeviceLocalAccountLocatorTests extends AndroidTestCase {

    // Basic smoke test that just checks that it doesn't throw when loading from CP2. We don't
    // care what CP2 actually contains for this.
    public void testShouldNotCrash() {
        final DeviceLocalAccountLocator sut = new DeviceLocalAccountLocator(
                getContext().getContentResolver(),
                new DeviceLocalAccountTypeFactory.Default(getContext()),
                Collections.<AccountWithDataSet>emptyList());
        sut.getDeviceLocalAccounts();
        // We didn't throw so it passed
    }

    public void test_getDeviceLocalAccounts_returnsEmptyListWhenQueryReturnsNull() {
        final DeviceLocalAccountLocator sut = createWithQueryResult(null);
        assertTrue(sut.getDeviceLocalAccounts().isEmpty());
    }

    public void test_getDeviceLocalAccounts_returnsEmptyListWhenNoRawContactsHaveDeviceType() {
        final DeviceLocalAccountLocator sut = createWithQueryResult(queryResult(
                        "user", "com.example",
                        "user", "com.example",
                        "user", "com.example"));
        assertTrue(sut.getDeviceLocalAccounts().isEmpty());
    }

    public void test_getDeviceLocalAccounts_returnsListWithItemForNullAccount() {
        final DeviceLocalAccountLocator sut = createWithQueryResult(queryResult(
                "user", "com.example",
                null, null,
                "user", "com.example",
                null, null));

        assertEquals(1, sut.getDeviceLocalAccounts().size());
    }

    public void test_getDeviceLocalAccounts_containsItemForEachDeviceAccount() {
        final DeviceLocalAccountTypeFactory stubFactory = new FakeDeviceAccountTypeFactory()
                .withDeviceTypes(null, "vnd.sec.contact.phone")
                .withSimTypes("vnd.sec.contact.sim");
        final DeviceLocalAccountLocator sut = new DeviceLocalAccountLocator(
                createStubResolverWithContentQueryResult(queryResult(
                        "user", "com.example",
                        "user", "com.example",
                        "phone_account", "vnd.sec.contact.phone",
                        null, null,
                        "phone_account", "vnd.sec.contact.phone",
                        "user", "com.example",
                        null, null,
                        "sim_account", "vnd.sec.contact.sim",
                        "sim_account_2", "vnd.sec.contact.sim"
                )), stubFactory,
                Collections.<AccountWithDataSet>emptyList());

        assertEquals(4, sut.getDeviceLocalAccounts().size());
    }

    public void test_getDeviceLocalAccounts_doesNotContainItemsForKnownAccounts() {
        final DeviceLocalAccountLocator sut = new DeviceLocalAccountLocator(
                getContext().getContentResolver(), new FakeDeviceAccountTypeFactory(),
                Arrays.asList(new AccountWithDataSet("user", "com.example", null),
                        new AccountWithDataSet("user1", "com.example", null),
                        new AccountWithDataSet("user", "com.example.1", null)));

        assertTrue("Selection should filter known accounts", sut.getSelection().contains("NOT IN (?,?)"));

        final List<String> args = Arrays.asList(sut.getSelectionArgs());
        assertEquals(2, args.size());
        assertTrue("Selection args is missing an expected value", args.contains("com.example"));
        assertTrue("Selection args is missing an expected value", args.contains("com.example.1"));
    }

    public void test_getDeviceLocalAccounts_includesAccountsFromSettings() {
        final DeviceLocalAccountTypeFactory stubFactory = new FakeDeviceAccountTypeFactory()
                .withDeviceTypes(null, "vnd.sec.contact.phone")
                .withSimTypes("vnd.sec.contact.sim");
        final DeviceLocalAccountLocator sut = new DeviceLocalAccountLocator(
                createContentResolverWithProvider(new FakeContactsProvider()
                        .withQueryResult(ContactsContract.Settings.CONTENT_URI, queryResult(
                                "phone_account", "vnd.sec.contact.phone",
                                "sim_account", "vnd.sec.contact.sim"
                ))), stubFactory, Collections.<AccountWithDataSet>emptyList());

        assertEquals(2, sut.getDeviceLocalAccounts().size());
    }

    public void test_getDeviceLocalAccounts_includesAccountsFromGroups() {
        final DeviceLocalAccountTypeFactory stubFactory = new FakeDeviceAccountTypeFactory()
                .withDeviceTypes(null, "vnd.sec.contact.phone")
                .withSimTypes("vnd.sec.contact.sim");
        final DeviceLocalAccountLocator sut = new DeviceLocalAccountLocator(
                createContentResolverWithProvider(new FakeContactsProvider()
                        .withQueryResult(ContactsContract.Groups.CONTENT_URI, queryResult(
                                "phone_account", "vnd.sec.contact.phone",
                                "sim_account", "vnd.sec.contact.sim"
                        ))), stubFactory, Collections.<AccountWithDataSet>emptyList());

        assertEquals(2, sut.getDeviceLocalAccounts().size());
    }

    public void test_getDeviceLocalAccounts_onlyQueriesRawContactsIfNecessary() {
        final DeviceLocalAccountTypeFactory stubFactory = new FakeDeviceAccountTypeFactory()
                .withDeviceTypes(null, "vnd.sec.contact.phone")
                .withSimTypes("vnd.sec.contact.sim");
        final FakeContactsProvider contactsProvider = new FakeContactsProvider()
                .withQueryResult(ContactsContract.Groups.CONTENT_URI, queryResult(
                        "phone_account", "vnd.sec.contact.phone",
                        "sim_account", "vnd.sec.contact.sim"
                ));
        final DeviceLocalAccountLocator sut = new DeviceLocalAccountLocator(
                createContentResolverWithProvider(contactsProvider), stubFactory,
                Collections.<AccountWithDataSet>emptyList());

        sut.getDeviceLocalAccounts();

        assertEquals(0, contactsProvider.getQueryCountFor(RawContacts.CONTENT_URI));
    }

    private DeviceLocalAccountLocator createWithQueryResult(
            Cursor cursor) {
        final DeviceLocalAccountLocator locator = new DeviceLocalAccountLocator(
                createStubResolverWithContentQueryResult(cursor),
                new DeviceLocalAccountTypeFactory.Default(getContext()),
                Collections.<AccountWithDataSet>emptyList());
        return locator;
    }

    private ContentResolver createContentResolverWithProvider(ContentProvider contactsProvider) {
        final MockContentResolver resolver = new MockContentResolver();
        resolver.addProvider(ContactsContract.AUTHORITY, contactsProvider);
        return resolver;
    }


    private ContentResolver createStubResolverWithContentQueryResult(Cursor cursor) {
        final MockContentResolver resolver = new MockContentResolver();
        resolver.addProvider(ContactsContract.AUTHORITY, new FakeContactsProvider()
                .withDefaultQueryResult(cursor));
        return resolver;
    }

    private Cursor queryResult(String... nameTypePairs) {
        final MatrixCursor cursor = new MatrixCursor(new String[]
                { RawContacts.ACCOUNT_NAME, RawContacts.ACCOUNT_TYPE, RawContacts.DATA_SET });
        for (int i = 0; i < nameTypePairs.length; i+=2) {
            cursor.newRow().add(nameTypePairs[i]).add(nameTypePairs[i+1])
                    .add(null);
        }
        return cursor;
    }

    private static class FakeContactsProvider extends MockContentProvider {
        public Cursor mNextQueryResult;
        public Map<Uri, Cursor> mNextResultMapping = new HashMap<>();
        public Map<Uri, Integer> mQueryCountMapping = new HashMap<>();

        public FakeContactsProvider() {}

        public FakeContactsProvider withDefaultQueryResult(Cursor cursor) {
            mNextQueryResult = cursor;
            return this;
        }

        public FakeContactsProvider withQueryResult(Uri uri, Cursor cursor) {
            mNextResultMapping.put(uri, cursor);
            return this;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                String sortOrder) {
            return query(uri, projection, selection, selectionArgs, sortOrder, null);
        }

        public int getQueryCountFor(Uri uri) {
            ensureCountInitialized(uri);
            return mQueryCountMapping.get(uri);
        }

        @Nullable
        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                String sortOrder, CancellationSignal cancellationSignal) {
            incrementQueryCount(uri);

            final Cursor result = mNextResultMapping.get(uri);
            if (result == null) {
                return mNextQueryResult;
            } else {
                return result;
            }
        }

        private void ensureCountInitialized(Uri uri) {
            if (!mQueryCountMapping.containsKey(uri)) {
                mQueryCountMapping.put(uri, 0);
            }
        }

        private void incrementQueryCount(Uri uri) {
            ensureCountInitialized(uri);
            final int count = mQueryCountMapping.get(uri);
            mQueryCountMapping.put(uri, count + 1);
        }
    }
}
