# RYSIndex

Просмотрщик рецептов, мод семьи RYS. Эта сессия также главная по общему кору `C:\Projects\RepYS\RYSCore`: темы, рисование и правила вида живут там, в его `CLAUDE.md`. Прочитай их перед правками интерфейса.

Новые замечания автора о виде и поведении, общие для семьи, записывай правилом в `RYSCore/CLAUDE.md`.

Папки `questscope/`, `ryspulse/`, `rysquests-companion/` и файлы `design/*` могут меняться другими чатами. В коммиты RYSIndex их не включать: добавляй только `common neoforge prodtest docs MAP.md CLAUDE.md gradle.properties`.

Карта проекта `MAP.md` собирается скриптом `_shared/tools/make-map.py` из корня группы, руками её не править.

Сборка требует соседний `../RYSCore` с выполненным `gradlew publish`.
