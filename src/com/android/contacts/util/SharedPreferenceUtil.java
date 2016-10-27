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

package com.android.contacts.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;

import com.android.contacts.common.model.SimCard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SharedPreferenceUtil {

    public static final String PREFERENCE_KEY_ACCOUNT_SYNC_OFF_DISMISSES =
            "num-of-dismisses-account-sync-off";

    public static final String PREFERENCE_KEY_GLOBAL_SYNC_OFF_DISMISSES =
            "num-of-dismisses-auto-sync-off";

    private static final String PREFERENCE_KEY_HAMBURGER_PROMO_DISPLAYED_BEFORE =
            "hamburgerPromoDisplayedBefore";

    private static final String PREFERENCE_KEY_HAMBURGER_MENU_CLICKED_BEFORE =
            "hamburgerMenuClickedBefore";

    private static final String PREFERENCE_KEY_HAMBURGER_PROMO_TRIGGER_ACTION_HAPPENED_BEFORE =
            "hamburgerPromoTriggerActionHappenedBefore";

    private static final String PREFERENCE_KEY_IMPORTED_SIM_CARDS =
            "importedSimCards";

    private static final String PREFERENCE_KEY_DISMISSED_SIM_CARDS =
            "dismissedSimCards";

    public static boolean getHamburgerPromoDisplayedBefore(Context context) {
        return getSharedPreferences(context)
                .getBoolean(PREFERENCE_KEY_HAMBURGER_PROMO_DISPLAYED_BEFORE, false);
    }

    public static void setHamburgerPromoDisplayedBefore(Context context) {
        getSharedPreferences(context).edit()
                .putBoolean(PREFERENCE_KEY_HAMBURGER_PROMO_DISPLAYED_BEFORE, true)
                .apply();
    }

    public static boolean getHamburgerMenuClickedBefore(Context context) {
        return getSharedPreferences(context)
                .getBoolean(PREFERENCE_KEY_HAMBURGER_MENU_CLICKED_BEFORE, false);
    }

    public static void setHamburgerMenuClickedBefore(Context context) {
        getSharedPreferences(context).edit()
                .putBoolean(PREFERENCE_KEY_HAMBURGER_MENU_CLICKED_BEFORE, true)
                .apply();
    }

    public static boolean getHamburgerPromoTriggerActionHappenedBefore(Context context) {
        return getSharedPreferences(context)
                .getBoolean(PREFERENCE_KEY_HAMBURGER_PROMO_TRIGGER_ACTION_HAPPENED_BEFORE, false);
    }

    public static void setHamburgerPromoTriggerActionHappenedBefore(Context context) {
        getSharedPreferences(context).edit()
                .putBoolean(PREFERENCE_KEY_HAMBURGER_PROMO_TRIGGER_ACTION_HAPPENED_BEFORE, true)
                .apply();
    }

    /**
     * Show hamburger promo if:
     * 1) Hamburger menu is never clicked before
     * 2) Hamburger menu promo is never displayed before
     * 3) There is at least one available user action
     *      (for now, available user actions to trigger to displayed hamburger promo are:
     *       a: QuickContact UI back to PeopleActivity
     *       b: Search action back to PeopleActivity)
     */
    public static boolean getShouldShowHamburgerPromo(Context context) {
        return !getHamburgerMenuClickedBefore(context)
                && getHamburgerPromoTriggerActionHappenedBefore(context)
                && !getHamburgerPromoDisplayedBefore(context);
    }

    protected static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences(context.getPackageName(), Context.MODE_PRIVATE);
    }

    public static int getNumOfDismissesForAutoSyncOff(Context context) {
        return getSharedPreferences(context).getInt(PREFERENCE_KEY_GLOBAL_SYNC_OFF_DISMISSES, 0);
    }

    public static void resetNumOfDismissesForAutoSyncOff(Context context) {
        final int value = getSharedPreferences(context).getInt(
                PREFERENCE_KEY_GLOBAL_SYNC_OFF_DISMISSES, 0);
        if (value != 0) {
            getSharedPreferences(context).edit()
                    .putInt(PREFERENCE_KEY_GLOBAL_SYNC_OFF_DISMISSES, 0).apply();
        }
    }

    public static void incNumOfDismissesForAutoSyncOff(Context context) {
        final int value = getSharedPreferences(context).getInt(
                PREFERENCE_KEY_GLOBAL_SYNC_OFF_DISMISSES, 0);
        getSharedPreferences(context).edit()
                .putInt(PREFERENCE_KEY_GLOBAL_SYNC_OFF_DISMISSES, value + 1).apply();
    }

    private static String buildSharedPrefsName(String accountName) {
        return accountName + "-" + PREFERENCE_KEY_ACCOUNT_SYNC_OFF_DISMISSES;
    }

    public static int getNumOfDismissesforAccountSyncOff(Context context, String accountName) {
        return getSharedPreferences(context).getInt(buildSharedPrefsName(accountName), 0);
    }

    public static void resetNumOfDismissesForAccountSyncOff(Context context, String accountName) {
        final int value = getSharedPreferences(context).getInt(
                buildSharedPrefsName(accountName), 0);
        if (value != 0) {
            getSharedPreferences(context).edit()
                    .putInt(buildSharedPrefsName(accountName), 0).apply();
        }
    }

    public static void incNumOfDismissesForAccountSyncOff(Context context, String accountName) {
        final int value = getSharedPreferences(context).getInt(
                buildSharedPrefsName(accountName), 0);
        getSharedPreferences(context).edit()
                .putInt(buildSharedPrefsName(accountName), value + 1).apply();
    }

    public static void persistSimStates(Context context, Collection<SimCard> sims) {
        final Set<String> imported = new HashSet<>(getImportedSims(context));
        final Set<String> dismissed = new HashSet<>(getDismissedSims(context));
        for (SimCard sim : sims) {
            if (sim.isImported()) {
                imported.add(sim.getSimId());
            } else {
                imported.remove(sim.getSimId());
            }
            if (sim.isDismissed()) {
                dismissed.add(sim.getSimId());
            } else {
                dismissed.remove(sim.getSimId());
            }
        }
        getSharedPreferences(context).edit()
                .putStringSet(PREFERENCE_KEY_IMPORTED_SIM_CARDS, imported)
                .putStringSet(PREFERENCE_KEY_DISMISSED_SIM_CARDS, dismissed)
                .apply();
    }

    public static List<SimCard> restoreSimStates(Context context, List<SimCard> sims) {
        final Set<String> imported = getImportedSims(context);
        final Set<String> dismissed = getDismissedSims(context);
        List<SimCard> result = new ArrayList<>();
        for (SimCard sim : sims) {
            result.add(sim.withImportAndDismissStates(imported.contains(sim.getSimId()),
                    dismissed.contains(sim.getSimId())));
        }
        return result;
    }

    private static Set<String> getImportedSims(Context context) {
        return getSharedPreferences(context)
                .getStringSet(PREFERENCE_KEY_IMPORTED_SIM_CARDS, Collections.<String>emptySet());
    }

    private static Set<String> getDismissedSims(Context context) {
        return getSharedPreferences(context)
                .getStringSet(PREFERENCE_KEY_DISMISSED_SIM_CARDS, Collections.<String>emptySet());
    }

    public static void clear(Context context) {
        getSharedPreferences(context).edit().clear().commit();
    }
}
