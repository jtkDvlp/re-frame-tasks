# Changelog

## [3.0.0](https://github.com/jtkDvlp/re-frame-tasks/compare/2.3.0...3.0.0) (2026-10-01)


### ⚠ BREAKING CHANGES

* 3.0.0 breaks with 2.x. The completion-key setters give way to `reg-completion-keys-for-effect`; a task carries `::claims` instead of `::effects`, and its event under `::event`; the interceptor ids are namespaced; `::unregister-and-dispatch-original` is an event only and carries the effect key before the original event; `org.clojure/clojure`, `re-frame` and `reagent` are `provided`; the function form of `wait-for`'s `tasks` takes the coeffects and the running tasks; and the licence is EPL-2.0 where it was MIT. What to do about each stands under "Upgrading from 2.x" in the README.
* `dispatch-debounce` and `flush-debounce` are now `dispatch-debounce!` and `flush-debounce!`, and the function form of `wait-for`'s `tasks` argument takes two arguments, the coeffects and the running tasks, where it took one.

### Bug Fixes

* run the events waiting on a task that a run completes itself ([4d9bf9f](https://github.com/jtkDvlp/re-frame-tasks/commit/4d9bf9f2ccf01a6406adf0d9184d955b1a1c7b75))


### Refactoring

* better docs, names and code structure ([dc0d748](https://github.com/jtkDvlp/re-frame-tasks/commit/dc0d7480d71af22be1b22280d3caf0b9c1e39b81))
* complete a task in one place instead of three ([8a2b8dc](https://github.com/jtkDvlp/re-frame-tasks/commit/8a2b8dcd843e93f8ff8e8fbb2f272e6d93b3722e))
* log through re-frame instead of timbre ([964fe83](https://github.com/jtkDvlp/re-frame-tasks/commit/964fe839256b3b90ed5b548bdc914be80a1133d8))


### Documentation

* bring the README in line with core.async-helpers ([f24aa9b](https://github.com/jtkDvlp/re-frame-tasks/commit/f24aa9b7c84d87af81d0376d462a6a7f50c216c5))
* name the licence switch in the upgrade table ([16f6594](https://github.com/jtkDvlp/re-frame-tasks/commit/16f6594381182f48cae128ebef43b87fa2e9fe71))
* name the licence switch in the upgrade table ([3ea08ab](https://github.com/jtkDvlp/re-frame-tasks/commit/3ea08ab1d1da9ad7e1091f98581e030d41b41435))
* put the ordering half into "The problem it solves" ([fa5391a](https://github.com/jtkDvlp/re-frame-tasks/commit/fa5391a4ee5c0042c4baf4a62d943734ce077c59))
* say what `::event` is for, and fix three cljdoc links ([5184df3](https://github.com/jtkDvlp/re-frame-tasks/commit/5184df3817c71fb4782fbbdb7ade4b7f80592bc1))

## [2.3.0](https://github.com/jtkDvlp/re-frame-tasks/compare/2.2.0...2.3.0) (2026-09-17)


### Bug Fixes

* let an event that an async coeffect resumes past its own task ([3c32e2f](https://github.com/jtkDvlp/re-frame-tasks/commit/3c32e2f4befa347efb5d792b3cc6eab81b76da3e))

  `wait-for` queued the dispatch that
  [re-frame-async-coeffects](https://github.com/jtkDvlp/re-frame-async-coeffects)
  sends once its coeffects resolve -- behind the very task that dispatch
  belongs to. An event combining `as-task` and `inject-acofx` therefore
  never ran, and its task never completed.
