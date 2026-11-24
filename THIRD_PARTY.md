# Сторонний код в составе RYSIndex

## Just Enough Items (JEI)

С версии 0.3.9 JEI в jar RYSIndex не вкладывается: это обязательная зависимость, игрок ставит его отдельно (19.42.0.379 или новее). RYSIndex компилируется против API JEI (MIT, автор mezz, https://github.com/mezz/JustEnoughItems) и берёт у него категории, рецепты и ингредиенты плагинов модов.

## Inter и Space Grotesk

Шрифты в RYSCore, `assets/ryscore/fonts/`. Copyright 2016 The Inter Project Authors (https://github.com/rsms/inter), Copyright 2020 The Space Grotesk Project Authors (https://github.com/floriankarsten/space-grotesk), SIL Open Font License 1.1. Текст лицензии лежит рядом со шрифтами (`OFL.txt`) и есть в jar RYSCore, который вложен в jar RYSIndex.

## RYSCore

Наш общий код (MPL-2.0, тот же владелец), вложен в `META-INF/jarjar/`.

## Свой код и графика

Код RYSIndex под MPL-2.0 (`LICENSE`), графика и название RYS: All Rights Reserved, см. `NOTICE.md`.
