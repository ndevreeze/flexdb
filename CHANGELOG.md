# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

# TODO - Figure out this format
	
## 0.3.0
### Changed
- surround all column and table names with double-quotes, wrt reserved (key)words. All tests succeed, but something else might break, quite a big change.
	
## 0.4.0 - 2020-06-20
### Changed
- renamed to flexdb (old: dynamicdb) and put in its own repo.
	
## [Unreleased]
### Changed
- Add a new arity to `make-widget-async` to provide a different widget shape.

## [0.1.1] - 2018-05-13
### Changed
- Documentation on how to make the widgets.

### Removed
- `make-widget-sync` - we're all async, all the time.

### Fixed
- Fixed widget maker to keep working when daylight savings switches over.

## 0.1.0 - 2018-05-13
### Added
- Files from the new template.
- Widget maker public API - `make-widget-sync`.

[Unreleased]: https://github.com/ndevreeze/flexdb/compare/0.1.1...HEAD
[0.1.1]: https://github.com/ndevreeze/flexdb/compare/0.1.0...0.1.1

