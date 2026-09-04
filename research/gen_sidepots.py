#!/usr/bin/env python3
"""Generate the Hold'em side-pot + hand-ranking CORPUS (HOLDEM.md §13.2).

The `LensOracleTest` pattern applied to poker: an INDEPENDENT authority proves
our model rather than us reading our own implementation and agreeing with
ourselves. The authority is `pokerkit` (MIT, University of Toronto CPSRG),
installed into a SCRATCH VENV — never vendored, never imported by shipped code,
never in the repo. What lands in the repo is the OUTPUT: scenarios and their
settled payouts, a corpus we own outright, so CI needs no Python and no network.

    python3 -m venv /tmp/pokervenv && /tmp/pokervenv/bin/pip install pokerkit
    /tmp/pokervenv/bin/python research/gen_sidepots.py \
        core/src/test/resources/holdem/sidepots.json \
        core/src/test/resources/holdem/hands.json

What is compared is the NET PAYOUT PER SEAT — the total that comes back to each
player, uncalled return and pot winnings together. That is the number that has
to be right, and it is convention-free: pokerkit books an all-fold as "the
aggressor's whole bet returns, the folders' chips are the pot" while `Pots`
books it as "the excess over the second-highest returns, the rest rides in a
single-contender pot". Those are the same money by different bookkeeping, and
comparing the decomposition instead of the payout would report a difference
that is not one.

⚠ Two things are deliberately NOT taken from the oracle:

  - the **odd-chip rule**. pokerkit hands the whole remainder to the
    lowest-indexed winner; Robert's Rules give odd chips one at a time starting
    from the first live player clockwise from the button, which is what
    `Pots.settle` implements and what its own test pins. Scenarios where a pot
    leaves 2+ odd chips are marked `exactPayout: false` and their payouts are
    compared only for conservation.
  - the **hand evaluator**. That gets its own corpus (`hands.json`): groups of
    2..6 hands over a shared board with pokerkit's winner set and its full
    tie-grouped ordering, so a ranking bug is diagnosed on its own rather than
    through a payout.
"""
import json
import random
import sys
import warnings

from pokerkit import Automation, NoLimitTexasHoldem, StandardHighHand

A = Automation
FULL = (A.ANTE_POSTING, A.BET_COLLECTION, A.BLIND_OR_STRADDLE_POSTING,
        A.CARD_BURNING, A.HOLE_CARDS_SHOWING_OR_MUCKING, A.HAND_KILLING,
        A.CHIPS_PUSHING, A.CHIPS_PULLING, A.RUNOUT_COUNT_SELECTION)
NO_SHOWDOWN = (A.ANTE_POSTING, A.BET_COLLECTION, A.BLIND_OR_STRADDLE_POSTING,
               A.CARD_BURNING, A.RUNOUT_COUNT_SELECTION)

RANKS = '23456789TJQKA'
SUITS = 'shdc'
DECK = [r + s for r in RANKS for s in SUITS]

SB, BB = 1, 2


def deal(rng, n):
    d = DECK[:]
    rng.shuffle(d)
    holes = [d[2 * i] + d[2 * i + 1] for i in range(n)]
    board = d[2 * n:2 * n + 5]
    return holes, board


def run(seed, n, stacks, holes, board, automations):
    """Drive one hand. Returns (state, raw contributions, folded flags, actions)."""
    rng = random.Random(seed ^ 0x5EED)
    st = NoLimitTexasHoldem.create_state(automations, True, 0, (SB, BB), BB, tuple(stacks), n)
    for h in holes:
        st.deal_hole(h)
    raw = [0] * n
    folded = [False] * n
    dealt = 0

    def sample():
        # the RAW contribution is the PEAK of (starting stack − live stack).
        # Reading `st.bets` instead loses the last actor of every round: the
        # automated BET_COLLECTION fires inside that action and zeroes the bet
        # before the sample sees it — which silently under-counted every hand
        # whose closing call was the biggest one.
        for i in range(n):
            raw[i] = max(raw[i], stacks[i] - st.stacks[i])

    sample()                                     # the blinds
    guard = 0
    while st.status and guard < 400:
        guard += 1
        if st.actor_index is not None:
            i = st.actor_index
            roll = rng.random()
            hi = st.max_completion_betting_or_raising_to_amount
            if roll < 0.22 and st.can_fold():
                st.fold()
                folded[i] = True
            elif roll < 0.55 and hi is not None and st.can_complete_bet_or_raise_to(hi):
                st.complete_bet_or_raise_to(hi)   # a shove: this is what makes side pots
            elif roll < 0.72 and hi is not None:
                # a partial raise, grid-free: somewhere between the minimum and all-in
                lo = st.min_completion_betting_or_raising_to_amount
                amt = lo if lo is None else rng.randint(lo, hi)
                if amt is not None and st.can_complete_bet_or_raise_to(amt):
                    st.complete_bet_or_raise_to(amt)
                else:
                    st.check_or_call()
            else:
                st.check_or_call()
            sample()
        elif st.can_deal_board():
            take = 3 if dealt == 0 else 1
            st.deal_board(''.join(board[dealt:dealt + take]))
            dealt += take
            sample()
        elif st.can_show_or_muck_hole_cards():
            # all remaining players are all-in: pokerkit puts the cards on
            # their backs BEFORE running the board out, and refuses to deal
            # until they are shown. Without this the loop broke out with an
            # empty board and every such hand was scored as a chop.
            st.show_or_muck_hole_cards(True)
        else:
            break
    sample()
    return st, raw, folded


def winners_of(pot_players, holes, board, folded):
    live = [i for i in pot_players if not folded[i]]
    if not live:
        return []
    b = ''.join(board)
    if len(b) < 10:                              # fewer than five board cards: no showdown
        return live
    hands = {i: StandardHighHand.from_game(holes[i], b) for i in live}
    best = max(hands.values())
    return [i for i in live if hands[i] == best]


def scenario(seed):
    rng = random.Random(seed)
    n = rng.randint(2, 6)
    # a deliberate mix: equal stacks make no side pots, wildly unequal ones make
    # several, and the tiny stacks are where the off-by-one lives
    shape = rng.random()
    if shape < 0.25:
        stacks = [rng.choice([100, 200, 500])] * n
    elif shape < 0.6:
        stacks = [rng.randint(3, 400) for _ in range(n)]
    else:
        stacks = [rng.choice([3, 5, 11, 27, 60, 140, 333, 1000]) for _ in range(n)]
    stacks = [max(1, s) for s in stacks]
    holes, board = deal(rng, n)

    st2, raw, folded = run(seed, n, stacks, holes, board, NO_SHOWDOWN)
    pots = [{'amount': p.amount, 'eligible': sorted(p.player_indices)} for p in st2.pots]
    st1, raw1, folded1 = run(seed, n, stacks, holes, board, FULL)
    if raw1 != raw or folded1 != folded:
        return None                              # the two runs diverged: drop it rather than guess
    final = list(st1.stacks)
    payout = [final[i] - (stacks[i] - raw[i]) for i in range(n)]
    if sum(payout) != sum(raw):
        return None


    dealt_board = [repr(c) for row in st2.board_cards for c in row]
    # `oddChips` = how many chips the two conventions may place differently.
    # pokerkit hands a pot's whole remainder to the first entry of its winner
    # list; Robert's Rules spread them one at a time from the button. With NO
    # remainder the two agree exactly, which is what `exactPayout` marks — an
    # earlier version allowed a remainder of 1 on the reasoning that both give
    # it to the lowest seat, and one scenario in three thousand proved that
    # wrong, so the flag is now strict and the odd-chip rule is pinned by its
    # own rules-derived test instead.
    odd = 0
    pot_winners = []
    for p in pots:
        w = winners_of(p['eligible'], holes, dealt_board, folded)
        pot_winners.append(w)
        odd += 0 if not w else p['amount'] % len(w)
    exact = odd == 0

    return {
        'seed': seed,
        'seats': [{'contrib': raw[i], 'folded': folded[i], 'hole': [holes[i][:2], holes[i][2:]]}
                  for i in range(n)],
        'board': dealt_board,
        # the odd-chip ORDER: seats ascending from the small blind, which is
        # index 0 in pokerkit's seating
        'order': list(range(n)),
        'pots': pots,
        'potWinners': pot_winners,
        'payout': payout,
        'exactPayout': exact,
        'oddChips': odd,
    }


def hand_group(seed):
    rng = random.Random(seed ^ 0xA11CE)
    n = rng.randint(2, 6)
    holes, board = deal(rng, n)
    b = ''.join(board)
    hands = [StandardHighHand.from_game(h, b) for h in holes]
    best = max(hands)
    return {
        'board': board,
        'holes': [[h[:2], h[2:]] for h in holes],
        'winners': [i for i, h in enumerate(hands) if h == best],
        'labels': [h.entry.label.value for h in hands],
        # the full ordering, strongest first, as groups of ties
        'order': [sorted(i for i, h in enumerate(hands) if h == v)
                  for v in sorted(set(hands), reverse=True)],
    }


def main():
    warnings.filterwarnings('ignore')          # pokerkit's "not recommended to deal" hint on explicit deals
    out_pots = sys.argv[1] if len(sys.argv) > 1 else 'sidepots.json'
    out_hands = sys.argv[2] if len(sys.argv) > 2 else 'hands.json'
    n_pots = int(sys.argv[3]) if len(sys.argv) > 3 else 3000
    n_hands = int(sys.argv[4]) if len(sys.argv) > 4 else 2000

    scenarios = []
    seed = 0
    dropped = 0
    while len(scenarios) < n_pots and seed < n_pots * 20:
        seed += 1
        try:
            s = scenario(seed)
        except Exception as e:                   # a state pokerkit refuses: skip it LOUDLY
            print(f'  seed {seed}: {type(e).__name__}: {e}', file=sys.stderr)
            dropped += 1
            continue
        if s is None:
            dropped += 1
            continue
        scenarios.append(s)
    with open(out_pots, 'w') as f:
        json.dump({'source': 'pokerkit (MIT) via research/gen_sidepots.py',
                   'scenarios': scenarios}, f, separators=(',', ':'))
    print(f'{len(scenarios)} scenarios ({dropped} dropped) -> {out_pots}')
    sides = sum(1 for s in scenarios if len(s['pots']) > 1)
    odd = sum(1 for s in scenarios if not s['exactPayout'])
    print(f'  {sides} with side pots, {odd} where the odd-chip rules diverge, '
          f'max seats {max(len(s["seats"]) for s in scenarios)}')

    groups = [hand_group(i) for i in range(n_hands)]
    with open(out_hands, 'w') as f:
        json.dump({'source': 'pokerkit StandardHighHand (MIT)', 'groups': groups},
                  f, separators=(',', ':'))
    cats = {}
    for g in groups:
        for l in g['labels']:
            cats[l] = cats.get(l, 0) + 1
    print(f'{len(groups)} hand groups -> {out_hands}')
    print('  ' + ', '.join(f'{k}:{v}' for k, v in sorted(cats.items(), key=lambda kv: -kv[1])))


if __name__ == '__main__':
    main()
