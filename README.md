# Spoiler Blocker

An Android app that hides spoilers on your own phone. You describe something you
don't want spoiled (an F1 race, a film) and switch a blocker on. While it is on,
posts about that topic in apps like YouTube and Instagram are covered with a
black box, and matching notifications are dismissed. It stays on until you
switch it off.

## Status

Design phase. There is no code yet.

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): how v1 works, the decisions
  behind it, what was rejected, and the open risks.
- [docs/BUILD_PLAN.md](docs/BUILD_PLAN.md): the phased plan for building v1.

Several decisions are still marked as proposed or waiting on an experiment.
The architecture document says which.

## Ground rules for this repository

This repository is public, so three things never get committed:

- API keys or other secrets.
- Model weights.
- Real screen captures or notification text from a phone. They contain other
  people's names and messages. Test data in the repo is written by hand.
