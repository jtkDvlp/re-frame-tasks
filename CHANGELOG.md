# Changelog

## [2.3.0](https://github.com/jtkDvlp/re-frame-tasks/compare/2.2.0...2.3.0) (2026-09-17)


### Bug Fixes

* let an event that an async coeffect resumes past its own task ([3c32e2f](https://github.com/jtkDvlp/re-frame-tasks/commit/3c32e2f4befa347efb5d792b3cc6eab81b76da3e))

  `wait-for` queued the dispatch that
  [re-frame-async-coeffects](https://github.com/jtkDvlp/re-frame-async-coeffects)
  sends once its coeffects resolve -- behind the very task that dispatch
  belongs to. An event combining `as-task` and `inject-acofx` therefore
  never ran, and its task never completed.
