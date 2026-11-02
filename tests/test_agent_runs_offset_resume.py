import asyncio

from src import agent_runs

EVENTS = [f'data: {{"delta":"{i}"}}\n\n' for i in range(4)] + ["data: [DONE]\n\n"]


async def _collect(session_id, run, **kw):
    return [e async for e in agent_runs.subscribe(session_id, run, **kw)]


def _run(coro_factory):
    async def scenario():
        sid = "offset-resume-test"

        async def stream():
            for e in EVENTS:
                yield e

        run = agent_runs.start(sid, stream())
        await run.task
        try:
            return await coro_factory(sid, run)
        finally:
            agent_runs._RUNS.pop(sid, None)

    return asyncio.run(scenario())


def test_default_replay_is_unchanged():
    got = _run(lambda sid, run: _collect(sid, run))
    assert got == EVENTS


def test_after_replays_only_missed_events_with_ids():
    got = _run(lambda sid, run: _collect(sid, run, after=2, with_ids=True))
    assert got == [f"id: {i}\n{EVENTS[i]}" for i in (3, 4)]


def test_after_past_end_yields_nothing():
    got = _run(lambda sid, run: _collect(sid, run, after=99, with_ids=True))
    assert got == []


def test_with_ids_alone_tags_from_zero():
    got = _run(lambda sid, run: _collect(sid, run, with_ids=True))
    assert got == [f"id: {i}\n{e}" for i, e in enumerate(EVENTS)]
