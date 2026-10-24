package com.vodhanel.minecraft.va_postal.mail;

import com.vodhanel.minecraft.va_postal.store.ListMember;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Who a mass mailing goes to (pure, so the rules are tested without a server). Two kinds of list:
 * <ul>
 *   <li>an office's subscribers ({@code office:<office>}): opt-in, only those who subscribed;</li>
 *   <li>a Towny town's residents ({@code town:<town>}): everyone who lives there, unless they opted out.</li>
 * </ul>
 * Either way only players with a mailbox (an address of their own) can receive one.
 */
public final class MailingLists {
    private MailingLists() {
    }

    public static String office_list(String office) {
        return "office:" + office.toLowerCase(Locale.ROOT).trim();
    }

    public static String town_list(String town) {
        return "town:" + town.toLowerCase(Locale.ROOT).trim();
    }

    /** True for a list everyone in the group is on unless they opt out (a town's). */
    public static boolean opt_out(String list) {
        return list.startsWith("town:");
    }

    /**
     * The players {@code list} reaches: for an opt-in list, those whose row says SUBSCRIBED; for an opt-out list,
     * {@code group} (the town's residents) less those whose row says OPTED_OUT. In a stable order, without repeats.
     */
    public static List<UUID> members(String list, Collection<UUID> group, List<ListMember> rows) {
        if (opt_out(list)) {
            Set<UUID> out_of = new HashSet<>();
            for (ListMember m : rows) {
                if (ListMember.OPTED_OUT.equals(m.mode())) {
                    out_of.add(m.player());
                }
            }
            Set<UUID> in = new LinkedHashSet<>();
            for (UUID p : group) {
                if (!out_of.contains(p)) {
                    in.add(p);
                }
            }
            return new ArrayList<>(in);
        }
        Set<UUID> in = new LinkedHashSet<>();
        for (ListMember m : rows) {
            if (ListMember.SUBSCRIBED.equals(m.mode())) {
                in.add(m.player());
            }
        }
        return new ArrayList<>(in);
    }

    /** What a mailing to {@code copies} mailboxes costs at {@code price} each (0 without an economy or a price). */
    public static double cost(int copies, double price) {
        return copies <= 0 || !(price > 0.0D) ? 0.0D : copies * price;
    }
}
