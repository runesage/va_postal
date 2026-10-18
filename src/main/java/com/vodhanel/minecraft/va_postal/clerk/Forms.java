package com.vodhanel.minecraft.va_postal.clerk;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The counter's forms: one text field and a row of buttons, shown as a dialog, or asked in chat where dialogs are
 * off ({@code Settings.Clerks.Dialogs: false}) or fail. In chat the player types the value, or a button's word
 * ("suggest"), or "cancel"; an empty answer or "ok" takes the value offered.
 */
public final class Forms {
    /** How long a chat question waits for its answer. */
    static final long CHAT_WAIT_MS = 120_000L;

    /** A button: its label, the word that picks it in chat, and what it does with the field's value. */
    public record Choice(String label, String word, BiConsumer<Player, String> act) {
    }

    /** A form: a title, lines of text, one field (label, value offered, longest answer) and its buttons; the first submits. */
    public record Form(String title, List<String> body, String field, String initial, int max, List<Choice> choices) {
    }

    private record Pending(Form form, long since) {
    }

    private static final Map<UUID, Pending> asking = new HashMap<>();

    private Forms() {
    }

    public static void show(Player player, Form form) {
        if (dialogs()) {
            try {
                player.showDialog(dialog(form));
                return;
            } catch (RuntimeException | LinkageError e) {
                Util.dinform("[Postal] Dialog unavailable (" + e.getMessage() + "); asking in chat.");
            }
        }
        ask_in_chat(player, form);
    }

    static boolean dialogs() {
        return GetConfig.bool_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.clerks.dialogs"), true);
    }

    @SuppressWarnings("UnstableApiUsage")
    private static Dialog dialog(Form form) {
        List<DialogBody> body = new ArrayList<>();
        for (String line : form.body()) {
            body.add(DialogBody.plainMessage(Text.component(Text.color(line))));
        }
        List<ActionButton> buttons = new ArrayList<>();
        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build();
        for (Choice c : form.choices()) {
            buttons.add(ActionButton.builder(Text.component(Text.color(c.label()))).width(100)
                    .action(DialogAction.customClick((response, audience) -> {
                        if (audience instanceof Player p) {
                            String value = response.getText("value");
                            run(() -> c.act().accept(p, value == null ? "" : value.trim()));
                        }
                    }, once)).build());
        }
        ActionButton cancel = ActionButton.builder(Component.text("Cancel")).width(100).build();
        DialogBase base = DialogBase.builder(Text.component(Text.color(form.title())))
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(body)
                .inputs(List.of(DialogInput.text("value", Text.component(Text.color(form.field())))
                        .initial(form.initial() == null ? "" : form.initial())
                        .maxLength(Math.max(1, form.max()))
                        .width(200)
                        .build()))
                .build();
        return Dialog.create(b -> b.empty().base(base)
                .type(DialogType.multiAction(buttons).exitAction(cancel).columns(Math.min(3, buttons.size())).build()));
    }

    private static void run(Runnable r) {
        VA_postal.plugin.getServer().getScheduler().runTask(VA_postal.plugin, r);
    }

    private static synchronized void ask_in_chat(Player player, Form form) {
        asking.put(player.getUniqueId(), new Pending(form, System.currentTimeMillis()));
        player.sendMessage(Text.color("&6&l" + form.title()));
        for (String line : form.body()) {
            player.sendMessage(Text.color("&7" + line));
        }
        StringBuilder words = new StringBuilder();
        for (int i = 1; i < form.choices().size(); i++) {
            words.append("&f").append(form.choices().get(i).word()).append("&7, ");
        }
        player.sendMessage(Text.color("&7Type the " + form.field().toLowerCase(Locale.ROOT)
                + (form.initial() == null || form.initial().isEmpty() ? "" : " (or &fok&7 for &f" + form.initial() + "&7)")
                + " in chat; or " + words + "&fcancel&7."));
    }

    /** Takes a player's chat line if a form is waiting for it (returns false otherwise). Runs the answer on the main thread. */
    static boolean answer(Player player, String typed) {
        Pending p;
        synchronized (Forms.class) {
            p = asking.get(player.getUniqueId());
            if (p == null) {
                return false;
            }
            asking.remove(player.getUniqueId());
        }
        if (System.currentTimeMillis() - p.since() > CHAT_WAIT_MS) {
            return false;
        }
        List<String> words = new ArrayList<>();
        for (Choice c : p.form().choices()) {
            words.add(c.word());
        }
        int pick = choose(typed, words);
        if (pick < 0) {
            run(() -> player.sendMessage(Text.color("&7Cancelled.")));
            return true;
        }
        String value = pick == 0 ? value(typed, p.form().initial()) : p.form().initial();
        Choice choice = p.form().choices().get(pick);
        run(() -> choice.act().accept(player, value == null ? "" : value));
        return true;
    }

    /** Which button a chat answer picks: -1 for "cancel", a button whose word was typed, else 0 (the field's value). */
    static int choose(String typed, List<String> words) {
        String t = typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT);
        if ("cancel".equals(t)) {
            return -1;
        }
        for (int i = 1; i < words.size(); i++) {
            if (t.equals(words.get(i).toLowerCase(Locale.ROOT))) {
                return i;
            }
        }
        return 0;
    }

    /** The field's value from a chat answer: the value offered for an empty answer or "ok", else what was typed. */
    static String value(String typed, String initial) {
        String t = typed == null ? "" : typed.trim();
        return t.isEmpty() || "ok".equalsIgnoreCase(t) || "yes".equalsIgnoreCase(t) ? initial : t;
    }

    /** Forgets a player's open question (they left). */
    static synchronized void forget(UUID player) {
        asking.remove(player);
    }
}
