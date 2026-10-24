package com.vodhanel.minecraft.va_postal.towny;

import java.util.UUID;

/**
 * The rules for post offices on Towny post office plots (docs/design/towny.md), on plain values so they can be tested
 * without Towny. {@link TownyPostOffices} reads the plots from Towny and applies these.
 */
public final class PostOfficePlots {
    /** The plot type Postal adds to Towny. */
    public static final String TYPE = "postoffice";

    private PostOfficePlots() {
    }

    /**
     * A town plot (town block) as Postal needs it: its town, a key naming the block ({@code world:x:z}), whether it's a
     * post office plot, its owner (null when the town owns it) and the town's mayor (null if none). NPC owners and
     * mayors are null.
     */
    public record Plot(String town, String key, boolean postoffice, UUID owner, UUID mayor) {
    }

    /** Who owns an office or an address on {@code plot}: its owner, else the mayor; null means the server. */
    public static UUID office_owner(Plot plot) {
        return plot.owner() != null ? plot.owner() : plot.mayor();
    }

    /**
     * Why {@code holder} can't open an office with a chest on {@code plot}, or null if they may. {@code plot} is null
     * outside towns (no Towny rule applies); {@code office_there} is true if an office already stands on the plot.
     */
    public static String charter_problem(Plot plot, UUID holder, boolean office_there) {
        if (plot == null) {
            return null;
        }
        if (!plot.postoffice()) {
            return "This is in " + plot.town() + ": a post office there must be on a post office plot. Ask the mayor"
                    + " for one (/plot set " + TYPE + ").";
        }
        if (office_there) {
            return "There's already a post office on this plot.";
        }
        if (plot.owner() != null) {
            return plot.owner().equals(holder) ? null : "This post office plot belongs to someone else.";
        }
        return holder != null && holder.equals(plot.mayor()) ? null
                : "This post office plot still belongs to " + plot.town() + ": buy it first (/plot claim), or ask the mayor.";
    }

    /**
     * Why {@code holder} can't make an address with a chest on {@code plot}, or null if they may: on a plot they own,
     * or, for the mayor or an assistant ({@code town_officer}), on one the town owns. {@code plot} is null outside
     * towns (no Towny rule applies).
     */
    public static String address_problem(Plot plot, UUID holder, boolean town_officer) {
        if (plot == null) {
            return null;
        }
        if (plot.owner() != null) {
            return plot.owner().equals(holder) ? null
                    : "This chest is on someone else's plot in " + plot.town() + ": an address goes on a plot you own.";
        }
        return town_officer ? null : "This plot belongs to " + plot.town() + ": an address goes on a plot you own (buy one"
                + " with /plot claim), or ask the mayor.";
    }

    /**
     * Why a box cabinet on {@code barrel} can't serve an office on {@code office}, or null. Only an office on a post
     * office plot has a rule: its cabinet stands on the same plot.
     */
    public static String cabinet_problem(Plot office, Plot barrel) {
        if (office == null || !office.postoffice()) {
            return null;
        }
        return barrel != null && office.key().equals(barrel.key()) ? null
                : "The box cabinet must stand on the post office's plot.";
    }

    /** Why an office on a post office plot can't be sold through Postal: it changes hands with its plot. */
    public static String sale_problem(String office_name) {
        return office_name + " stands on a town post office plot: it changes hands with the plot. Sell the plot instead"
                + " (/plot forsale)";
    }
}
