package dev.duelcraft.client;

import dev.duelcraft.DuelCraftData;
import dev.duelcraft.cards.CardDb;
import dev.duelcraft.client.gen.PromptWidgets;
import dev.duelcraft.duel.Declarable;
import dev.duelcraft.duel.DuelState;
import dev.duelcraft.duel.MsgReader.Loc;
import dev.duelcraft.duel.OcgCore;
import dev.duelcraft.duel.Responses;
import dev.duelcraft.gen.HintStrings;
import dev.duelcraft.gen.MdEncodings;
import dev.duelcraft.gen.Payloads;
import dev.duelcraft.gen.Prompts.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * The duel screen (systems: duel_screen; ui_widgets sheet): the field from DuelStatePayload, a hover
 * preview with the Master Duel card text, the log, and one widget per pending prompt (PromptWidgets is
 * generated from the sheets, so every prompt has a widget or the build fails).
 */
public final class DuelScreen extends Screen implements PromptWidgets<DuelScreen.Widget> {
	/** A clickable action: a button or a card's context-menu entry. */
	record Action(String label, Supplier<byte[]> answer) {
	}

	/** A card the player can pick in a picker overlay. */
	record Pick(int code, Loc where, String note) {
	}

	/** One prompt's UI: buttons, actions on field cards, an optional picker or zone selection. */
	final class Widget {
		String title = "";
		final List<Action> buttons = new ArrayList<>();
		final Map<String, List<Action>> cardActions = new LinkedHashMap<>();
		List<Pick> picks;
		int min, max;
		boolean cancelable, single;
		final Set<Integer> chosen = new LinkedHashSet<>();
		java.util.function.Function<List<Integer>, byte[]> pickAnswer;
		Set<Integer> chosenAlready = Set.of();
		int zoneCount, zoneBlocked;
		boolean zoneMode;
		final List<int[]> zones = new ArrayList<>();
		long flagsAvailable;
		int flagCount;
		boolean flagsAreRaces;
		long flagsChosen;
		int focusCode;
	}

	private Widget widget;
	private Object widgetFor;
	private List<Action> menu;
	private int menuX, menuY;
	private int hoverCode, hoverCid;
	private EditBox search;
	private final List<int[]> buttonRects = new ArrayList<>();
	private final List<Action> buttonActions = new ArrayList<>();
	private final Map<String, int[]> cardRects = new LinkedHashMap<>();
	private final List<int[]> pickRects = new ArrayList<>();
	private int cw, ch, fieldX, fieldY, leftW, rightX;

	public DuelScreen() {
		super(Component.literal("Duel"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		search = null;
		widgetFor = null;
	}

	// ------------------------------------------------------------------ helpers

	static String key(int con, int loc, int seq) {
		return con + ":" + loc + ":" + seq;
	}

	private void send(byte[] answer) {
		if (ClientDuel.prompt == null) {
			return;
		}
		ClientPlayNetworking.send(new Payloads.DuelResponsePayload(ClientDuel.duelId, ClientDuel.promptSeq, answer));
		ClientDuel.prompt = null;
		widget = null;
		menu = null;
		if (search != null) {
			removeWidget(search);
			search = null;
		}
	}

	static String cardName(int code) {
		CardDb.CardInfo c = CardRender.byPasscode(code);
		return c == null ? "card " + code : c.name();
	}

	static int cidOf(int code) {
		CardDb.CardInfo c = CardRender.byPasscode(code);
		return c == null ? 0 : c.cid();
	}

	static String desc(long d, int code) {
		if (d == 0) {
			return code == 0 ? "OK" : "Activate " + cardName(code);
		}
		if (d > 10000 && d < 0x100000000L && CardRender.byPasscode((int) d) != null) {
			return cardName((int) d); // HINT_SELECTMSG before SELECT_PLACE/POSITION names the card
		}
		if ((d >>> 20) != 0) {
			int owner = (int) (d >>> 20);
			int n = (int) (d & 0xFFFFF);
			return cardName(owner) + ": effect " + (n + 1);
		}
		String t = HintStrings.TEXT.get((int) d);
		return t != null ? t : switch ((int) d) {
			case 1150 -> "Activate";
			case 1151 -> "Normal Summon";
			case 1152 -> "Special Summon";
			case 1153 -> "Set";
			case 1160 -> "Activate it as a Pendulum Scale";
			default -> "Option " + d;
		};
	}

	static String locName(int loc) {
		return switch (loc & 0x7F) {
			case OcgCore.LOCATION_DECK -> "Deck";
			case OcgCore.LOCATION_HAND -> "Hand";
			case OcgCore.LOCATION_MZONE -> "Monster Zone";
			case OcgCore.LOCATION_SZONE -> "Spell & Trap Zone";
			case OcgCore.LOCATION_GRAVE -> "GY";
			case OcgCore.LOCATION_REMOVED -> "Banished";
			case OcgCore.LOCATION_EXTRA -> "Extra Deck";
			default -> "";
		} + ((loc & OcgCore.LOCATION_OVERLAY) != 0 ? " (material)" : "");
	}

	private String hintTitle(String fallback) {
		long h = ClientDuel.promptHint;
		return h == 0 ? fallback : desc(h, 0);
	}

	private Widget picker(String title, List<Pick> picks, int min, int max, boolean cancelable, java.util.function.Function<List<Integer>, byte[]> answer) {
		Widget w = new Widget();
		w.title = hintTitle(title);
		w.picks = picks;
		w.min = min;
		w.max = max;
		w.cancelable = cancelable;
		w.pickAnswer = answer;
		return w;
	}

	// ------------------------------------------------------------------ widgets (ui_widgets sheet)

	@Override
	public Widget cardActions(SelectIdleCmd p) {
		Widget w = new Widget();
		w.title = "Your Main Phase: click a card to use it";
		addAll(w, p.summonable(), "Normal Summon", 0, e -> new int[] {e.con(), e.loc(), e.seq()}, null);
		addAll(w, p.spsummonable(), "Special Summon", 1, e -> new int[] {e.con(), e.loc(), e.seq()}, null);
		addAll(w, p.repositionable(), "Change position", 2, e -> new int[] {e.con(), e.loc(), e.seq()}, null);
		addAll(w, p.msetable(), "Set", 3, e -> new int[] {e.con(), e.loc(), e.seq()}, null);
		addAll(w, p.ssetable(), "Set", 4, e -> new int[] {e.con(), e.loc(), e.seq()}, null);
		for (int i = 0; i < p.activatable().size(); i++) {
			var a = p.activatable().get(i);
			int idx = i;
			Action act = new Action(desc(a.desc(), a.code()), () -> Responses.i32(idx << 16 | 5));
			if (onFieldOrHand(a.loc())) {
				w.cardActions.computeIfAbsent(key(a.con(), a.loc(), a.seq()), k -> new ArrayList<>()).add(act);
			} else {
				w.buttons.add(new Action(act.label() + " (" + locName(a.loc()) + ")", act.answer()));
			}
		}
		if (p.canBp() != 0) {
			w.buttons.add(new Action("Battle Phase", () -> Responses.i32(6)));
		}
		if (p.canEp() != 0) {
			w.buttons.add(new Action("End Turn", () -> Responses.i32(7)));
		}
		if (p.canShuffle() != 0) {
			w.buttons.add(new Action("Shuffle hand", () -> Responses.i32(8)));
		}
		return w;
	}

	private static boolean onFieldOrHand(int loc) {
		return loc == OcgCore.LOCATION_HAND || loc == OcgCore.LOCATION_MZONE || loc == OcgCore.LOCATION_SZONE;
	}

	private <E> void addAll(Widget w, List<E> list, String label, int action, java.util.function.Function<E, int[]> where, String suffix) {
		for (int i = 0; i < list.size(); i++) {
			int[] l = where.apply(list.get(i));
			int idx = i;
			w.cardActions.computeIfAbsent(key(l[0], l[1], l[2]), k -> new ArrayList<>()).add(new Action(label, () -> Responses.i32(idx << 16 | action)));
		}
	}

	@Override
	public Widget phaseActions(SelectBattleCmd p) {
		Widget w = new Widget();
		w.title = "Battle Phase: click a monster to attack";
		for (int i = 0; i < p.attackers().size(); i++) {
			var a = p.attackers().get(i);
			int idx = i;
			w.cardActions.computeIfAbsent(key(a.con(), a.loc(), a.seq()), k -> new ArrayList<>())
				.add(new Action(a.direct() != 0 ? "Attack (direct possible)" : "Attack", () -> Responses.i32(idx << 16 | 1)));
		}
		for (int i = 0; i < p.chains().size(); i++) {
			var c = p.chains().get(i);
			int idx = i;
			Action act = new Action(desc(c.desc(), c.code()), () -> Responses.i32(idx << 16));
			if (onFieldOrHand(c.loc())) {
				w.cardActions.computeIfAbsent(key(c.con(), c.loc(), c.seq()), k -> new ArrayList<>()).add(act);
			} else {
				w.buttons.add(act);
			}
		}
		if (p.canM2() != 0) {
			w.buttons.add(new Action("Main Phase 2", () -> Responses.i32(2)));
		}
		if (p.canEp() != 0) {
			w.buttons.add(new Action("End Turn", () -> Responses.i32(3)));
		}
		return w;
	}

	@Override
	public Widget yesNo(SelectEffectYn p) {
		Widget w = new Widget();
		w.title = desc(p.desc(), p.code()) + "?";
		w.focusCode = p.code();
		w.buttons.add(new Action("Yes", () -> Responses.i32(1)));
		w.buttons.add(new Action("No", () -> Responses.i32(0)));
		return w;
	}

	@Override
	public Widget yesNo(SelectYesNo p) {
		Widget w = new Widget();
		w.title = desc(p.desc(), 0) + "?";
		w.buttons.add(new Action("Yes", () -> Responses.i32(1)));
		w.buttons.add(new Action("No", () -> Responses.i32(0)));
		return w;
	}

	@Override
	public Widget optionList(SelectOption p) {
		Widget w = new Widget();
		w.title = hintTitle("Choose an option");
		for (int i = 0; i < p.options().size(); i++) {
			int idx = i;
			w.buttons.add(new Action(desc(p.options().get(i).desc(), 0), () -> Responses.i32(idx)));
		}
		return w;
	}

	@Override
	public Widget optionList(AnnounceNumber p) {
		Widget w = new Widget();
		w.title = hintTitle("Declare a number");
		for (int i = 0; i < p.options().size(); i++) {
			int idx = i;
			w.buttons.add(new Action(String.valueOf(p.options().get(i).value()), () -> Responses.i32(idx)));
		}
		return w;
	}

	@Override
	public Widget cardPicker(SelectCard p) {
		List<Pick> picks = p.cards().stream().map(c -> new Pick(c.code(), c.where(), locName(c.where().loc()))).toList();
		return picker("Select card(s)", picks, p.min(), p.max(), p.cancelable() != 0, Responses::cards);
	}

	@Override
	public Widget cardPicker(SelectTribute p) {
		List<Pick> picks = p.cards().stream().map(c -> new Pick(c.code(), new Loc(c.con(), c.loc(), c.seq(), 0),
			c.releaseParam() > 1 ? "counts as " + c.releaseParam() : locName(c.loc()))).toList();
		return picker("Select Tribute(s)", picks, 1, p.max(), p.cancelable() != 0, Responses::cards);
	}

	@Override
	public Widget cardPicker(SelectSum p) {
		List<Pick> picks = p.cards().stream().map(c -> new Pick(c.code(), c.where(), "value " + (c.param() & 0xFFFF)
			+ ((c.param() >>> 16) != 0 ? "/" + (c.param() >>> 16) : ""))).toList();
		Widget w = picker("Select cards totalling " + p.target(), picks, Math.max(1, p.min()), p.max() == 0 ? picks.size() : p.max(), false, Responses::cards);
		w.title = w.title + " (total " + p.target() + ")";
		return w;
	}

	@Override
	public Widget cardPickerStep(SelectUnselectCard p) {
		List<Pick> picks = new ArrayList<>();
		p.selectable().forEach(c -> picks.add(new Pick(c.code(), c.where(), locName(c.where().loc()))));
		p.selected().forEach(c -> picks.add(new Pick(c.code(), c.where(), "chosen")));
		Widget w = picker("Select card(s)", picks, 1, 1, p.finishable() != 0 || p.cancelable() != 0, idx -> Responses.unselect(idx.getFirst()));
		w.single = true;
		Set<Integer> already = new LinkedHashSet<>();
		for (int i = p.selectable().size(); i < picks.size(); i++) {
			already.add(i);
		}
		w.chosenAlready = already;
		if (p.finishable() != 0) {
			w.buttons.add(new Action("Finish", () -> Responses.i32(-1)));
		} else if (p.cancelable() != 0) {
			w.buttons.add(new Action("Cancel", () -> Responses.i32(-1)));
		}
		return w;
	}

	@Override
	public Widget chainPrompt(SelectChain p) {
		Widget w = new Widget();
		w.title = p.forced() != 0 ? "Activate an effect (required)" : "Chain an effect?";
		List<Pick> picks = p.chains().stream().map(c -> new Pick(c.code(), c.where(), desc(c.desc(), c.code()))).toList();
		w.picks = picks;
		w.min = 1;
		w.max = 1;
		w.single = true;
		w.pickAnswer = idx -> Responses.i32(idx.getFirst());
		if (p.forced() == 0) {
			w.buttons.add(new Action("Don't chain", () -> Responses.i32(-1)));
		}
		return w;
	}

	@Override
	public Widget zonePicker(SelectPlace p) {
		Widget w = new Widget();
		w.title = "Choose a zone" + (ClientDuel.promptHint > 10000 ? " for " + desc(ClientDuel.promptHint, 0) : "");
		w.zoneCount = p.count();
		w.zoneBlocked = p.blocked();
		w.zoneMode = true;
		return w;
	}

	@Override
	public Widget zonePicker(SelectDisfield p) {
		Widget w = new Widget();
		w.title = "Choose a zone" + (ClientDuel.promptHint > 10000 ? " for " + desc(ClientDuel.promptHint, 0) : "");
		w.zoneCount = p.count();
		w.zoneBlocked = p.blocked();
		w.zoneMode = true;
		return w;
	}

	@Override
	public Widget positionPicker(SelectPosition p) {
		Widget w = new Widget();
		w.title = "Choose a battle position for " + cardName(p.code());
		w.focusCode = p.code();
		String[] names = {"Face-up Attack", "Face-down Attack", "Face-up Defense", "Face-down Defense"};
		for (int b = 0; b < 4; b++) {
			int pos = 1 << b;
			if ((p.positions() & pos) != 0) {
				w.buttons.add(new Action(names[b], () -> Responses.i32(pos)));
			}
		}
		return w;
	}

	@Override
	public Widget sortList(SortChain p) {
		Widget w = new Widget();
		w.title = "Order of simultaneous effects";
		w.buttons.add(new Action("Keep this order", Responses::keepOrder));
		return w;
	}

	@Override
	public Widget sortList(SortCard p) {
		Widget w = new Widget();
		w.title = "Order the cards";
		w.buttons.add(new Action("Keep this order", Responses::keepOrder));
		return w;
	}

	@Override
	public Widget counterPicker(SelectCounter p) {
		Widget w = new Widget();
		w.title = "Remove " + p.count() + " counter(s)";
		int[] take = new int[p.cards().size()];
		int left = p.count();
		for (int i = 0; i < take.length && left > 0; i++) {
			take[i] = Math.min(left, p.cards().get(i).counters());
			left -= take[i];
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < take.length; i++) {
			if (take[i] > 0) {
				sb.append(take[i]).append(" from ").append(cardName(p.cards().get(i).code())).append("  ");
			}
		}
		w.buttons.add(new Action("Remove " + sb.toString().trim(), () -> Responses.counters(take)));
		return w;
	}

	@Override
	public Widget rps(RockPaperScissors p) {
		Widget w = new Widget();
		w.title = "Rock, paper, scissors!";
		w.buttons.add(new Action("Rock", () -> Responses.i32(2)));
		w.buttons.add(new Action("Paper", () -> Responses.i32(3)));
		w.buttons.add(new Action("Scissors", () -> Responses.i32(1)));
		return w;
	}

	@Override
	public Widget flagPicker(AnnounceRace p) {
		Widget w = new Widget();
		w.title = hintTitle("Declare a Type") + " (" + p.count() + ")";
		w.flagsAvailable = p.available();
		w.flagCount = p.count();
		w.flagsAreRaces = true;
		return w;
	}

	@Override
	public Widget flagPicker(AnnounceAttrib p) {
		Widget w = new Widget();
		w.title = hintTitle("Declare an Attribute") + " (" + p.count() + ")";
		w.flagsAvailable = p.available() & 0xFFFFFFFFL;
		w.flagCount = p.count();
		return w;
	}

	@Override
	public Widget cardNameSearch(AnnounceCard p) {
		Widget w = new Widget();
		w.title = "Declare a card name: type to search";
		List<Long> ops = p.opcodes().stream().map(AnnounceCard.OpcodesEntry::op).toList();
		search = new EditBox(font, rightX + 4, height - 120, width - rightX - 8, 14, Component.literal("search"));
		search.setResponder(text -> {
			w.buttons.clear();
			CardDb db = DuelCraftData.db();
			if (db == null || text.length() < 2) {
				return;
			}
			String q = text.toLowerCase();
			for (CardDb.CardInfo c : db.playable()) {
				if (c.name().toLowerCase().contains(q) && Declarable.check(c, ops)) {
					w.buttons.add(new Action(c.name(), () -> Responses.i32(c.passcode())));
					if (w.buttons.size() >= 6) {
						break;
					}
				}
			}
		});
		addRenderableWidget(search);
		setFocused(search);
		return w;
	}

	// ------------------------------------------------------------------ layout + drawing

	private void layout() {
		leftW = Math.max(110, width * 22 / 100);
		rightX = width - Math.max(140, width * 27 / 100);
		ch = Math.max(24, Math.min(64, (height - 12) * 10 / 74));
		cw = ch * 64 / 92;
		int fieldW = 7 * (cw + 2);
		fieldX = leftW + Math.max(4, (rightX - leftW - fieldW) / 2);
		fieldY = 4;
	}

	/** Column (0-6) and row (0-6) of a zone from this player's point of view; null if not drawn on the grid. */
	private int[] cell(int con, int loc, int seq) {
		boolean me = con == ClientDuel.seat;
		int side = me ? 1 : -1;
		return switch (loc) {
			case OcgCore.LOCATION_MZONE -> seq <= 4 ? new int[] {me ? 1 + seq : 5 - seq, me ? 4 : 2}
				: new int[] {(seq == 5) == me ? 2 : 4, 3};
			case OcgCore.LOCATION_SZONE -> seq <= 4 ? new int[] {me ? 1 + seq : 5 - seq, me ? 5 : 1}
				: seq == 5 ? new int[] {me ? 0 : 6, me ? 4 : 2} : null;
			case OcgCore.LOCATION_GRAVE -> new int[] {me ? 6 : 0, me ? 4 : 2};
			case OcgCore.LOCATION_DECK -> new int[] {me ? 6 : 0, me ? 5 : 1};
			case OcgCore.LOCATION_EXTRA -> new int[] {me ? 0 : 6, me ? 5 : 1};
			case OcgCore.LOCATION_REMOVED -> new int[] {me ? 6 : 0, 3};
			default -> side == 0 ? null : null;
		};
	}

	private int[] rectOf(int col, int row) {
		int x = fieldX + col * (cw + 2), y = fieldY + row * (ch + 2);
		return new int[] {x, y, cw, ch};
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
		g.fill(0, 0, width, height, 0xF0101420);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		layout();
		if (ClientDuel.prompt != null && widgetFor != ClientDuel.prompt) {
			widgetFor = ClientDuel.prompt;
			if (search != null) {
				removeWidget(search);
				search = null;
			}
			// auto-pass empty optional chain prompts (the engine asks after nearly every action)
			if (ClientDuel.prompt instanceof SelectChain sc && sc.chains().isEmpty() && sc.forced() == 0) {
				send(Responses.i32(-1));
			} else {
				widget = widgetFor(ClientDuel.prompt);
			}
			menu = null;
		}
		if (ClientDuel.prompt == null) {
			widget = null;
		}
		super.extractRenderState(g, mx, my, a);
		hoverCode = 0;
		hoverCid = 0;
		cardRects.clear();
		buttonRects.clear();
		buttonActions.clear();
		pickRects.clear();
		drawField(g, mx, my);
		drawRight(g, mx, my);
		if (widget != null && widget.picks != null) {
			drawPicker(g, mx, my);
		}
		if (menu != null) {
			drawMenu(g, mx, my);
		}
		drawPreview(g);
	}

	private void drawField(GuiGraphicsExtractor g, int mx, int my) {
		// zone outlines
		for (int row = 1; row <= 5; row++) {
			for (int col = 0; col < 7; col++) {
				if (row == 3 && col != 2 && col != 4 && col != 0 && col != 6) {
					continue;
				}
				int[] r = rectOf(col, row);
				g.outline(r[0], r[1], r[2], r[3], 0x405080B0);
			}
		}
		for (DuelState.FieldCard c : ClientDuel.cards) {
			if (c.empty() || c.location() == OcgCore.LOCATION_HAND) {
				continue;
			}
			int[] cell = cell(c.controller(), c.location(), c.seq());
			if (cell == null) {
				continue;
			}
			boolean pile = c.location() == OcgCore.LOCATION_GRAVE || c.location() == OcgCore.LOCATION_DECK
				|| c.location() == OcgCore.LOCATION_EXTRA || c.location() == OcgCore.LOCATION_REMOVED;
			if (pile && !isTop(c)) {
				continue;
			}
			int[] r = rectOf(cell[0], cell[1]);
			drawFieldCard(g, c, r, mx, my, pile ? pileCount(c) : 0);
		}
		// hands
		for (int side = 0; side < 2; side++) {
			int con = side == 0 ? ClientDuel.seat : 1 - ClientDuel.seat;
			List<DuelState.FieldCard> hand = ClientDuel.at(con, OcgCore.LOCATION_HAND).stream().filter(c -> !c.empty()).toList();
			int row = side == 0 ? 6 : 0;
			int span = 7 * (cw + 2) - 2, n = hand.size();
			int step = n <= 1 ? 0 : Math.min(cw + 2, (span - cw) / (n - 1));
			int x0 = fieldX + (span - (n <= 1 ? cw : step * (n - 1) + cw)) / 2;
			for (int i = 0; i < n; i++) {
				int[] r = {x0 + i * step, fieldY + row * (ch + 2) + (side == 0 ? 0 : -ch / 3), cw, ch};
				drawFieldCard(g, hand.get(i), r, mx, my, 0);
			}
		}
		// zone selection
		if (widget != null && widget.zoneMode) {
			for (int bit = 0; bit < 32; bit++) {
				if ((widget.zoneBlocked >>> bit & 1) != 0) {
					continue;
				}
				int[] z = zoneOfBit(bit);
				if (z == null) {
					continue;
				}
				int[] cell = cell(z[0], z[1], z[2]);
				if (cell == null) {
					continue;
				}
				int[] r = rectOf(cell[0], cell[1]);
				boolean hover = in(mx, my, r);
				g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], hover ? 0x8040FF40 : 0x4040C040);
				cardRects.put("zone:" + bit, r);
			}
		}
		// turn banner / result
		String banner = ClientDuel.result.isEmpty() ? (ClientDuel.turnPlayer == ClientDuel.seat ? "Your turn" : ClientDuel.opponent + "'s turn")
			+ " · " + phaseName(ClientDuel.phase) : ClientDuel.result;
		int[] mid = rectOf(3, 3);
		g.centeredText(font, banner, mid[0] + cw / 2, mid[1] + ch / 2 - 4, ClientDuel.result.isEmpty() ? 0xFFE0C060 : 0xFFFFFF60);
	}

	private boolean isTop(DuelState.FieldCard c) {
		int maxSeq = -1;
		for (DuelState.FieldCard o : ClientDuel.cards) {
			if (!o.empty() && o.controller() == c.controller() && o.location() == c.location()) {
				maxSeq = Math.max(maxSeq, o.seq());
			}
		}
		return c.seq() == maxSeq;
	}

	private int pileCount(DuelState.FieldCard c) {
		return (int) ClientDuel.cards.stream().filter(o -> !o.empty() && o.controller() == c.controller() && o.location() == c.location()).count();
	}

	private void drawFieldCard(GuiGraphicsExtractor g, DuelState.FieldCard c, int[] r, int mx, int my, int count) {
		int cid = c.faceDown() && c.controller() != ClientDuel.seat ? 0 : cidOf(c.code());
		boolean showBack = c.code() == 0 || (c.faceDown() && c.location() != OcgCore.LOCATION_HAND && c.controller() != ClientDuel.seat)
			|| c.location() == OcgCore.LOCATION_DECK;
		int drawCid = showBack ? 0 : cid;
		if (c.defense() && (c.location() == OcgCore.LOCATION_MZONE)) {
			// defense: draw rotated as a wide card (art only), keeps the grid simple
			int h2 = r[2], w2 = r[3];
			int x = r[0] + (r[2] - w2) / 2, y = r[1] + (r[3] - h2) / 2;
			g.fill(x, y, x + w2, y + h2, 0xFF303040);
			if (drawCid != 0) {
				CardRender.art(g, drawCid, x + (w2 - h2) / 2, y, h2);
			} else {
				g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, CardRender.BACK, x, y, 0, 0, w2, h2, w2, h2);
			}
			if (c.faceDown() && c.controller() == ClientDuel.seat) {
				g.outline(x, y, w2, h2, 0xFF808080);
			}
		} else {
			CardRender.draw(g, drawCid, r[0], r[1], r[2], r[3]);
			if (c.faceDown() && c.controller() == ClientDuel.seat && c.location() != OcgCore.LOCATION_HAND && !showBack) {
				g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0x50000000); // own set card: dimmed face
			}
		}
		String k = key(c.controller(), c.location(), c.seq());
		cardRects.put(k, r);
		boolean selectable = widget != null && widget.cardActions.containsKey(k);
		if (selectable) {
			g.outline(r[0] - 1, r[1] - 1, r[2] + 2, r[3] + 2, 0xFF40FF40);
		}
		if (c.location() == OcgCore.LOCATION_MZONE && !c.faceDown()) {
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate(r[0] + 1, r[1] + r[3] - 9);
			pose.scale(0.5F, 0.5F);
			g.fill(-1, -1, r[2] * 2 - 2, 18, 0xB0000000);
			g.text(font, "ATK " + c.atk(), 0, 0, 0xFFFF9060);
			if ((c.type() & CardDb.TYPE_LINK) == 0) {
				g.text(font, "DEF " + c.def(), 0, 9, 0xFF80C0FF);
			}
			pose.popMatrix();
		}
		if (count > 1) {
			g.text(font, String.valueOf(count), r[0] + 2, r[1] + 2, 0xFFFFFF80);
		}
		if (in(mx, my, r) && drawCid != 0) {
			hoverCid = drawCid;
			hoverCode = c.code();
		}
	}

	/** SELECT_PLACE bit -> (controller, location, sequence). */
	private int[] zoneOfBit(int bit) {
		int con = bit >= 16 ? 1 - ClientDuel.seat : ClientDuel.seat;
		int b = bit % 16;
		if (b < 7) {
			return new int[] {con, OcgCore.LOCATION_MZONE, b};
		}
		if (b >= 8 && b < 14) {
			return new int[] {con, OcgCore.LOCATION_SZONE, b - 8};
		}
		return null;
	}

	static String phaseName(int phase) {
		return switch (phase) {
			case 0x01 -> "Draw Phase";
			case 0x02 -> "Standby Phase";
			case 0x04 -> "Main Phase 1";
			case 0x08, 0x10, 0x20, 0x40, 0x80 -> "Battle Phase";
			case 0x100 -> "Main Phase 2";
			case 0x200 -> "End Phase";
			default -> "";
		};
	}

	private void drawRight(GuiGraphicsExtractor g, int mx, int my) {
		int x = rightX + 4, w = width - rightX - 8, y = 4;
		g.fill(rightX, 0, width, height, 0xC0181C28);
		int me = ClientDuel.seat;
		lpBar(g, x, y, w, ClientDuel.opponent, ClientDuel.lp[1 - me], 0xFFE05050);
		y += 14;
		lpBar(g, x, y, w, "You", ClientDuel.lp[me], 0xFF50A0E0);
		y += 16;
		g.text(font, "Turn " + ClientDuel.turn, x, y, 0xFFB0B0B0);
		y += 12;
		// log
		int promptTop = height - 8 - (widget == null ? 0 : 14 + Math.max(1, widget.buttons.size()) * 16);
		int lines = Math.max(0, (promptTop - y - 4) / 9);
		List<FormattedCharSequence> wrapped = new ArrayList<>();
		for (String l : ClientDuel.log) {
			wrapped.addAll(font.split(Component.literal(l), w));
		}
		for (int i = Math.max(0, wrapped.size() - lines); i < wrapped.size(); i++) {
			g.text(font, wrapped.get(i), x, y, 0xFFCCCCCC);
			y += 9;
		}
		// prompt panel
		if (widget != null) {
			int py = promptTop;
			g.fill(rightX + 2, py - 2, width - 2, height - 2, 0xE0283048);
			for (FormattedCharSequence t : font.split(Component.literal(widget.title), w)) {
				g.text(font, t, x, py, 0xFFFFE070);
				py += 10;
			}
			py += 2;
			drawFlagButtons(g, mx, my, x, w);
			for (Action act : widget.buttons) {
				int[] r = {x, py, w, 14};
				button(g, r, act.label(), mx, my, true);
				buttonRects.add(r);
				buttonActions.add(act);
				py += 16;
			}
			if (widget.zoneMode) {
				g.text(font, "Click a green zone (" + (widget.zoneCount - widget.zones.size()) + " left)", x, py, 0xFFA0FFA0);
			}
		} else if (ClientDuel.active() && ClientDuel.result.isEmpty()) {
			g.text(font, Component.translatable("gui.duelcraft.waiting"), x, height - 14, 0xFF909090);
		} else if (!ClientDuel.result.isEmpty()) {
			g.text(font, "Press Esc to leave the duel", x, height - 14, 0xFFB0B0B0);
			if (ClientDuel.rewardCid != 0) {
				CardRender.draw(g, ClientDuel.rewardCid, x, height - 30 - ch, cw, ch);
				g.text(font, "Reward!", x + cw + 4, height - 30 - ch / 2, 0xFFFFE070);
			}
		}
	}

	private void drawFlagButtons(GuiGraphicsExtractor g, int mx, int my, int x, int w) {
		if (widget.flagCount <= 0) {
			return;
		}
		// flag toggles become buttons on the fly
		widget.buttons.removeIf(a -> a.label().startsWith("[") || a.label().equals("Confirm"));
		Map<Long, String> names = new LinkedHashMap<>();
		if (widget.flagsAreRaces) {
			MdEncodings.RACE_NAMES.forEach(names::put);
		} else {
			MdEncodings.ATTRIBUTE_NAMES.forEach((k, v) -> names.put((long) k, v));
		}
		List<Action> toggles = new ArrayList<>();
		for (var e : names.entrySet()) {
			long bit = e.getKey();
			if (bit != 0 && (widget.flagsAvailable & bit) != 0) {
				boolean on = (widget.flagsChosen & bit) != 0;
				toggles.add(new Action("[" + (on ? "x" : " ") + "] " + e.getValue(), () -> {
					widget.flagsChosen ^= bit;
					return null;
				}));
			}
		}
		if (Long.bitCount(widget.flagsChosen) == widget.flagCount) {
			long chosen = widget.flagsChosen;
			boolean races = widget.flagsAreRaces;
			toggles.add(new Action("Confirm", () -> races ? Responses.i64(chosen) : Responses.i32((int) chosen)));
		}
		widget.buttons.addAll(0, toggles);
	}

	private void lpBar(GuiGraphicsExtractor g, int x, int y, int w, String name, int lp, int color) {
		g.fill(x, y + 9, x + w, y + 12, 0xFF303030);
		g.fill(x, y + 9, x + (int) (w * Math.min(1.0, lp / 8000.0)), y + 12, color);
		g.text(font, name, x, y, 0xFFFFFFFF);
		String s = lp + " LP";
		g.text(font, s, x + w - font.width(s), y, 0xFFFFFFFF);
	}

	private void button(GuiGraphicsExtractor g, int[] r, String label, int mx, int my, boolean enabled) {
		boolean hover = enabled && in(mx, my, r);
		g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], !enabled ? 0xFF303030 : hover ? 0xFF5070B0 : 0xFF3A4A70);
		g.outline(r[0], r[1], r[2], r[3], 0xFF8090C0);
		String t = font.plainSubstrByWidth(label, r[2] - 6);
		g.text(font, t, r[0] + 3, r[1] + (r[3] - 8) / 2, enabled ? 0xFFFFFFFF : 0xFF808080);
	}

	private void drawPicker(GuiGraphicsExtractor g, int mx, int my) {
		List<Pick> picks = widget.picks;
		int pw = Math.min(rightX - leftW - 8, 6 * (cw + 6) + 12);
		int cols = Math.max(1, (pw - 12) / (cw + 6));
		int rows = (picks.size() + cols - 1) / cols;
		int ph = Math.min(height - 20, rows * (ch + 14) + 40);
		int px = leftW + (rightX - leftW - pw) / 2, py = (height - ph) / 2;
		g.fill(px, py, px + pw, py + ph, 0xF0202838);
		g.outline(px, py, pw, ph, 0xFFE0C060);
		g.text(font, font.plainSubstrByWidth(widget.title, pw - 8), px + 6, py + 5, 0xFFFFE070);
		for (int i = 0; i < picks.size(); i++) {
			Pick p = picks.get(i);
			int x = px + 6 + (i % cols) * (cw + 6), y = py + 18 + (i / cols) * (ch + 14);
			int[] r = {x, y, cw, ch};
			int cid = cidOf(p.code());
			CardRender.draw(g, cid, x, y, cw, ch);
			boolean sel = widget.chosen.contains(i) || widget.chosenAlready.contains(i);
			if (sel) {
				g.outline(x - 2, y - 2, cw + 4, ch + 4, 0xFF40FF40);
			}
			g.text(font, font.plainSubstrByWidth(p.note(), cw + 4), x, y + ch + 2, 0xFFB0B0B0);
			pickRects.add(r);
			if (in(mx, my, r)) {
				hoverCid = cid;
				hoverCode = p.code();
			}
		}
		if (!widget.single) {
			int n = widget.chosen.size();
			boolean ok = n >= widget.min && n <= widget.max;
			int[] r = {px + pw - 70, py + ph - 18, 64, 14};
			button(g, r, "Confirm " + n + "/" + widget.max, mx, my, ok);
			if (ok) {
				buttonRects.add(r);
				List<Integer> chosen = new ArrayList<>(widget.chosen);
				var ans = widget.pickAnswer;
				buttonActions.add(new Action("confirm", () -> ans.apply(chosen)));
			}
			if (widget.cancelable) {
				int[] c = {px + 6, py + ph - 18, 50, 14};
				button(g, c, "Cancel", mx, my, true);
				buttonRects.add(c);
				buttonActions.add(new Action("cancel", () -> Responses.i32(-1)));
			}
		}
	}

	private void drawMenu(GuiGraphicsExtractor g, int mx, int my) {
		int w = 10;
		for (Action a : menu) {
			w = Math.max(w, font.width(a.label()) + 8);
		}
		int x = Math.min(menuX, width - w - 2), y = Math.min(menuY, height - menu.size() * 14 - 2);
		for (Action a : menu) {
			int[] r = {x, y, w, 13};
			button(g, r, a.label(), mx, my, true);
			buttonRects.add(r);
			buttonActions.add(a);
			y += 14;
		}
	}

	private void drawPreview(GuiGraphicsExtractor g) {
		int cid = hoverCid != 0 ? hoverCid : (widget != null && widget.focusCode != 0 ? cidOf(widget.focusCode) : lastPreview);
		if (cid == 0) {
			g.text(font, "Hover a card to read it", 6, 6, 0xFF808080);
			return;
		}
		lastPreview = cid;
		CardDb.CardInfo c = CardRender.info(cid);
		int x = 4, w = leftW - 8, size = Math.min(w, height / 2 - 10);
		CardRender.art(g, cid, x + (w - size) / 2, 4, size);
		int y = size + 8;
		if (c == null) {
			return;
		}
		for (FormattedCharSequence t : font.split(Component.literal(c.name()), w)) {
			g.text(font, t, x, y, 0xFFFFE070);
			y += 10;
		}
		for (FormattedCharSequence t : font.split(Component.literal(CardRender.typeLine(c)), w)) {
			g.text(font, t, x, y, 0xFFB0C0E0);
			y += 10;
		}
		String stats = CardRender.statsLine(c);
		if (!stats.isEmpty()) {
			for (FormattedCharSequence t : font.split(Component.literal(stats), w)) {
				g.text(font, t, x, y, 0xFFFFFFFF);
				y += 10;
			}
		}
		for (FormattedCharSequence t : font.split(Component.literal(c.text()), w)) {
			if (y > height - 10) {
				break;
			}
			g.text(font, t, x, y, 0xFFD0D0D0);
			y += 9;
		}
	}

	private int lastPreview;

	private static boolean in(double mx, double my, int[] r) {
		return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		double mx = e.x(), my = e.y();
		for (int i = buttonRects.size() - 1; i >= 0; i--) {
			if (in(mx, my, buttonRects.get(i))) {
				byte[] ans = buttonActions.get(i).answer().get();
				menu = null;
				if (ans != null) {
					send(ans);
				}
				return true;
			}
		}
		menu = null;
		if (widget != null && widget.picks != null) {
			for (int i = 0; i < pickRects.size(); i++) {
				if (in(mx, my, pickRects.get(i))) {
					if (widget.single) {
						if (!widget.chosenAlready.contains(i) || widget.pickAnswer != null) {
							send(widget.pickAnswer.apply(List.of(i)));
						}
					} else if (!widget.chosen.remove(i) && widget.chosen.size() < widget.max) {
						widget.chosen.add(i);
					}
					return true;
				}
			}
		}
		for (Map.Entry<String, int[]> en : cardRects.entrySet()) {
			if (!in(mx, my, en.getValue())) {
				continue;
			}
			String k = en.getKey();
			if (k.startsWith("zone:") && widget != null) {
				int[] z = zoneOfBit(Integer.parseInt(k.substring(5)));
				widget.zones.add(z);
				widget.zoneBlocked |= 1 << Integer.parseInt(k.substring(5));
				if (widget.zones.size() >= widget.zoneCount) {
					send(Responses.places(new ArrayList<>(widget.zones)));
				}
				return true;
			}
			if (widget != null && widget.cardActions.containsKey(k)) {
				List<Action> acts = widget.cardActions.get(k);
				if (acts.size() == 1) {
					send(acts.getFirst().answer().get());
				} else {
					menu = acts;
					menuX = (int) mx;
					menuY = (int) my;
				}
				return true;
			}
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (DuelCraftClient.DUEL_KEY.matches(e) && (search == null || !search.isFocused())) {
			onClose();
			return true;
		}
		return super.keyPressed(e);
	}

	// ------------------------------------------------------------------ test bridge (TestBridge only)

	String describe() {
		StringBuilder sb = new StringBuilder();
		sb.append("turn ").append(ClientDuel.turn).append(" phase ").append(phaseName(ClientDuel.phase)).append(" LP ").append(ClientDuel.lp[0])
			.append('/').append(ClientDuel.lp[1]).append(" seat ").append(ClientDuel.seat).append(" result '").append(ClientDuel.result).append("'"+"\n");
		sb.append("prompt ").append(ClientDuel.prompt == null ? "none" : ClientDuel.prompt.getClass().getSimpleName()).append('\n');
		if (widget != null) {
			sb.append("title ").append(widget.title).append('\n');
			for (Action a : widget.buttons) {
				sb.append("button ").append(a.label()).append('\n');
			}
			widget.cardActions.forEach((k, v) -> sb.append("card ").append(k).append(' ').append(cardAt(k)).append(" -> ")
				.append(v.stream().map(Action::label).toList()).append('\n'));
			if (widget.picks != null) {
				for (int i = 0; i < widget.picks.size(); i++) {
					sb.append("pick ").append(i).append(' ').append(cardName(widget.picks.get(i).code())).append(" (").append(widget.picks.get(i).note()).append(")\n");
				}
			}
			if (widget.zoneMode) {
				sb.append("zones blocked ").append(Integer.toHexString(widget.zoneBlocked)).append('\n');
			}
		}
		for (int i = Math.max(0, ClientDuel.log.size() - 6); i < ClientDuel.log.size(); i++) {
			sb.append("log ").append(ClientDuel.log.get(i)).append('\n');
		}
		return sb.toString();
	}

	private String cardAt(String k) {
		String[] p = k.split(":");
		DuelState.FieldCard c = ClientDuel.at(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
		return c == null || c.empty() ? "?" : cardName(c.code());
	}

	String pressLabel(String label) {
		if (widget == null) {
			return "no prompt";
		}
		for (Action a : widget.buttons) {
			if (a.label().toLowerCase().startsWith(label.toLowerCase())) {
				byte[] ans = a.answer().get();
				if (ans != null) {
					send(ans);
				}
				return "pressed " + a.label();
			}
		}
		if (widget.picks != null && label.startsWith("pick ")) {
			for (String n : label.substring(5).split(",")) {
				int i = Integer.parseInt(n.trim());
				if (widget.single) {
					send(widget.pickAnswer.apply(List.of(i)));
					return "picked " + i;
				}
				widget.chosen.add(i);
			}
			if (widget.chosen.size() >= widget.min) {
				List<Integer> chosen = new ArrayList<>(widget.chosen);
				send(widget.pickAnswer.apply(chosen));
				return "picked " + chosen;
			}
			return "chosen " + widget.chosen;
		}
		if (label.startsWith("action ")) {
			String[] p = label.substring(7).split(" ", 2);
			List<Action> acts = widget.cardActions.get(p[0]);
			if (acts != null) {
				for (Action a : acts) {
					if (p.length < 2 || a.label().toLowerCase().startsWith(p[1].toLowerCase())) {
						send(a.answer().get());
						return "did " + a.label() + " on " + p[0];
					}
				}
			}
			return "no action on " + p[0];
		}
		if (label.startsWith("zone ")) {
			int bit = Integer.parseInt(label.substring(5).trim());
			widget.zones.add(zoneOfBit(bit));
			if (widget.zones.size() >= widget.zoneCount) {
				send(Responses.places(new ArrayList<>(widget.zones)));
			}
			return "zone " + bit;
		}
		return "no button " + label;
	}

	/** Test bridge: answer the current prompt through its widget (summon > attack > activate > set > buttons). */
	String autoStep() {
		if (widget == null) {
			return "no prompt";
		}
		for (String want : new String[] {"Normal Summon", "Special Summon", "Attack", "Activate", "Set"}) {
			for (var en : widget.cardActions.entrySet()) {
				for (Action a : en.getValue()) {
					if (a.label().startsWith(want) && !(want.equals("Set") && ClientDuel.turn % 2 != 0)) {
						send(a.answer().get());
						return "auto " + a.label() + " " + en.getKey() + " " + cardAt(en.getKey());
					}
				}
			}
		}
		if (widget.zoneMode) {
			for (int bit = 0; bit < 32; bit++) {
				if ((widget.zoneBlocked >>> bit & 1) == 0 && zoneOfBit(bit) != null) {
					return pressLabel("zone " + bit);
				}
			}
		}
		if (widget.picks != null && !widget.picks.isEmpty()) {
			if (!widget.single) {
				StringBuilder idx = new StringBuilder();
				for (int i = 0; i < Math.max(1, widget.min); i++) {
					idx.append(i == 0 ? "" : ",").append(i);
				}
				return pressLabel("pick " + idx);
			}
			for (Action a : widget.buttons) {
				if (a.label().startsWith("Don't")) {
					send(a.answer().get());
					return "auto " + a.label();
				}
			}
			return pressLabel("pick 0");
		}
		for (String want : new String[] {"Yes", "Face-up Attack", "Battle Phase", "Main Phase 2", "End Turn", "Keep", "Remove", "Rock", "Confirm", "Finish"}) {
			for (Action a : widget.buttons) {
				if (a.label().startsWith(want)) {
					byte[] ans = a.answer().get();
					if (ans != null) {
						send(ans);
					}
					return "auto " + a.label();
				}
			}
		}
		if (!widget.buttons.isEmpty()) {
			Action a = widget.buttons.getFirst();
			byte[] ans = a.answer().get();
			if (ans != null) {
				send(ans);
			}
			return "auto first " + a.label();
		}
		return "auto: nothing to do for " + widget.title;
	}

	String clickCard(int con, int loc, int seq) {
		int[] r = cardRects.get(key(con, loc, seq));
		if (r == null) {
			return "no card rect";
		}
		mouseClicked(new MouseButtonEvent(r[0] + r[2] / 2.0, r[1] + r[3] / 2.0, new net.minecraft.client.input.MouseButtonInfo(0, 0)), false);
		return "clicked card" + (menu != null ? " (menu: " + menu.stream().map(Action::label).toList() + ")" : "");
	}

	@Override
	public void onClose() {
		if (!ClientDuel.result.isEmpty()) {
			ClientDuel.leave();
		}
		super.onClose();
	}
}
