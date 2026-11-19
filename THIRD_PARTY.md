# Сторонний код в составе RYSIndex

## Just Enough Items (JEI)

- Автор: mezz. Репозиторий: https://github.com/mezz/JustEnoughItems
- Лицензия: MIT. Текст: `neoforge/src/main/resources/META-INF/licenses/JEI-LICENSE.txt`, в jar мода лежит по пути `META-INF/licenses/JEI-LICENSE.txt`.
- Как используется: неизменённый jar JEI вложен в jar RYSIndex (`META-INF/jarjar/`). Версия задаётся свойством `jei_embed_version` в `gradle.properties`.
- Зачем: плагины модов написаны под API JEI. Вложенный JEI загружает их, а RYSIndex берёт у него категории, рецепты и ингредиенты. Интерфейс JEI при этом отключён его же штатным API.

Если в папке mods есть свой JEI, загрузчик NeoForge берёт его, даже когда он старее вложенного. Минимальная версия, с которой работает мод: 19.42.0.379.

## Inter и Space Grotesk

Шрифты в RYSCore, `assets/ryscore/fonts/`. Copyright 2016 The Inter Project Authors (https://github.com/rsms/inter), Copyright 2020 The Space Grotesk Project Authors (https://github.com/floriankarsten/space-grotesk), SIL Open Font License 1.1. Текст лицензии лежит рядом со шрифтами (`OFL.txt`) и есть в jar RYSCore, который вложен в jar RYSIndex.

## RYSCore

Наш общий код (MPL-2.0, тот же владелец), вложен в `META-INF/jarjar/`.

## Свой код и графика

Код RYSIndex под MPL-2.0 (`LICENSE`), графика и название RYS: All Rights Reserved, см. `NOTICE.md`.
