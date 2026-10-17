package com.vodhanel.minecraft.va_postal.economy;

/**
 * Settling postage held in escrow (docs/economy.md, "Postage escrow"). When mail is addressed, Postal holds
 * the out-of-town price (plus any COD surcharge) at Central. On delivery the real price is known from the
 * office that first picked the mail up and the office that delivered it:
 * <ul>
 *   <li>same office: half to Central, half to that office;</li>
 *   <li>different offices: a third each to Central, the sending office and the delivering office;</li>
 *   <li>a COD surcharge: half to Central, half to the sending office;</li>
 *   <li>whatever was held beyond the price goes back to the sender.</li>
 * </ul>
 * Pure functions, so the policy can be unit tested without a server.
 */
public final class Postage {
    private Postage() {
    }

    /** Where held postage goes. The four parts add up to exactly what was held. */
    public static final class Split {
        public final double central;
        public final double origin;
        public final double dest;
        public final double refund;

        Split(double central, double origin, double dest, double refund) {
            this.central = central;
            this.origin = origin;
            this.dest = dest;
            this.refund = refund;
        }
    }

    /**
     * @param base_held postage or shipping held when the mail was addressed
     * @param cod_held  COD surcharge held (0 if none)
     * @param price     the postage or shipping price for the route the mail actually took
     * @param local     true if it was picked up and delivered by the same office
     */
    public static Split settle(double base_held, double cod_held, double price, boolean local) {
        double held = Math.max(0.0D, base_held);
        double base = Math.min(Math.max(0.0D, price), held); // never charge more than was held
        double cod = Math.max(0.0D, cod_held);
        double central;
        double origin;
        double dest = 0.0D;
        if (local) {
            origin = base / 2.0D;
            central = base - origin;
        } else {
            origin = base / 3.0D;
            dest = origin;
            central = base - 2.0D * origin;
        }
        double cod_office = cod / 2.0D;
        origin += cod_office;
        central += cod - cod_office;
        return new Split(central, origin, dest, held - base);
    }
}
