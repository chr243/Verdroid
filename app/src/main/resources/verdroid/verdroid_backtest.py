#!/usr/bin/env python3
"""Verdroid backtest harness (shipped by the app, overwritten on every run; do not edit).

A strategy is a .py file in Documents/Verdroid/Internal/strategies/ that defines

    def generate_signals(df, params) -> pandas.Series   # target position per bar, 0..1 (or -1..1 with allow_short)

df has columns time (epoch s, UTC), open, high, low, close, volume and a DatetimeIndex. The value at bar t is the
position you WANT after seeing bar t's close; the harness trades it at bar t+1 (signals shifted one bar => no lookahead).
Optional: PARAMS = {...} defaults, merged with the params passed by the tool.
"""
import argparse, importlib.util, json, math, os, sys

MARK = "@@VERDROID_BACKTEST@@"

def out(obj):
    print(MARK + json.dumps(obj, default=float)); sys.stdout.flush()

def main():
    ap = argparse.ArgumentParser()
    for a in ("strategy", "csv", "out_dir", "name", "symbol", "interval"): ap.add_argument("--" + a, required=True)
    ap.add_argument("--start", type=int, required=True); ap.add_argument("--end", type=int, required=True)
    ap.add_argument("--bar_seconds", type=int, required=True)
    ap.add_argument("--params", default="{}"); ap.add_argument("--capital", type=float, default=10000.0)
    ap.add_argument("--fee_percent", type=float, default=0.1); ap.add_argument("--slippage_percent", type=float, default=0.05)
    ap.add_argument("--position_percent", type=float, default=100.0); ap.add_argument("--allow_short", default="false")
    ap.add_argument("--plot", default="true")
    a = ap.parse_args()
    try:
        import numpy as np, pandas as pd
    except ImportError as e:
        out({"ok": False, "missing_packages": True, "error": "Python packages missing: %s. Install with: pip install pandas numpy matplotlib" % e}); return

    df = pd.read_csv(a.csv)
    df = df[(df.time >= a.start) & (df.time < a.end)].drop_duplicates("time").sort_values("time").reset_index(drop=True)
    if len(df) < 3:
        out({"ok": False, "error": "not enough candles in range (%d)" % len(df)}); return
    df.index = pd.to_datetime(df.time, unit="s", utc=True)

    spec = importlib.util.spec_from_file_location("strategy", a.strategy)
    mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)
    if not hasattr(mod, "generate_signals"):
        out({"ok": False, "error": "strategy must define generate_signals(df, params)"}); return
    params = dict(getattr(mod, "PARAMS", {}) or {}); params.update(json.loads(a.params or "{}"))

    sig = mod.generate_signals(df.copy(), params)
    sig = pd.Series(sig, index=df.index) if not isinstance(sig, pd.Series) else sig.reindex(df.index)
    lo = -1.0 if a.allow_short.lower() == "true" else 0.0
    sig = sig.astype(float).fillna(0.0).clip(lo, 1.0)
    pos = sig.shift(1).fillna(0.0) * (a.position_percent / 100.0)        # no lookahead: act on the next bar

    cost = (a.fee_percent + a.slippage_percent) / 100.0
    ret_bar = df.close.pct_change().fillna(0.0)
    # Entering/leaving at the open of bar t: approximate with close-to-close returns, pay cost on turnover.
    turnover = pos.diff().abs().fillna(pos.abs())
    strat = pos * ret_bar - turnover * cost
    equity = a.capital * (1.0 + strat).cumprod()

    years = (len(df) - 1) * a.bar_seconds / (365.25 * 86400)
    total = equity.iloc[-1] / a.capital - 1
    cagr = (equity.iloc[-1] / a.capital) ** (1 / years) - 1 if years > 0 and equity.iloc[-1] > 0 else 0.0
    dd = ((equity.cummax() - equity) / equity.cummax()).max()
    sd = strat.std(ddof=1)
    sharpe = (strat.mean() / sd * math.sqrt(365.25 * 86400 / a.bar_seconds)) if sd and sd > 0 else 0.0
    bh = df.close.iloc[-1] / df.close.iloc[0] - 1

    # Trades = runs of non-zero position.
    trades, cur = [], None
    for t, p, e in zip(df.index, pos.values, equity.values):
        if p != 0 and cur is None: cur = {"entry_time": str(t), "e0": e, "side": "long" if p > 0 else "short"}
        elif p == 0 and cur is not None:
            cur["exit_time"] = str(t); cur["return_pct"] = round((e / cur.pop("e0") - 1) * 100, 2); trades.append(cur); cur = None
    if cur is not None:
        cur["exit_time"] = str(df.index[-1]); cur["return_pct"] = round((equity.iloc[-1] / cur.pop("e0") - 1) * 100, 2); trades.append(cur)
    wins = sum(1 for t in trades if t["return_pct"] > 0)

    os.makedirs(a.out_dir, exist_ok=True)
    base = os.path.join(a.out_dir, "%s_%s_%s" % (a.name, a.symbol, a.interval))
    pd.DataFrame({"time": df.index.astype(str), "close": df.close.values, "position": pos.values, "equity": equity.values}).to_csv(base + "_equity.csv", index=False)
    png = None
    if a.plot.lower() == "true":
        try:
            import matplotlib; matplotlib.use("Agg"); import matplotlib.pyplot as plt
            fig, ax = plt.subplots(figsize=(9, 4.5))
            ax.plot(df.index, equity.values, label=a.name)
            ax.plot(df.index, a.capital * df.close.values / df.close.iloc[0], label="buy & hold", alpha=0.6)
            ax.set_title("%s %s %s" % (a.name, a.symbol, a.interval)); ax.legend(); ax.grid(alpha=0.3); fig.tight_layout()
            png = base + "_equity.png"; fig.savefig(png, dpi=110); plt.close(fig)
        except Exception as e:  # matplotlib optional
            png = None

    out({"ok": True, "strategy": a.name, "symbol": a.symbol, "interval": a.interval, "candles": len(df),
         "from": str(df.index[0]), "to": str(df.index[-1]), "params": params,
         "initial_capital": a.capital, "final_equity": round(float(equity.iloc[-1]), 2),
         "total_return_pct": round(total * 100, 2), "cagr_pct": round(cagr * 100, 2), "max_drawdown_pct": round(dd * 100, 2),
         "sharpe": round(float(sharpe), 2), "trades": len(trades), "win_rate_pct": round(wins * 100 / len(trades), 2) if trades else 0.0,
         "buy_hold_return_pct": round(bh * 100, 2), "vs_buy_hold_pct": round((total - bh) * 100, 2),
         "exposure_pct": round(float((pos != 0).mean() * 100), 2),
         "trade_list": trades[-20:], "equity_csv": base + "_equity.csv", "equity_png": png})

if __name__ == "__main__":
    try: main()
    except Exception as e:
        import traceback; out({"ok": False, "error": "%s: %s" % (type(e).__name__, e), "traceback": traceback.format_exc()[-2000:]})
