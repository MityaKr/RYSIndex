package works.repka.ryscope.neoforge.ae2;

/** Вход шаблона обработки AE2, которому миксин дал варианты замещения. */
public interface AlternativesHolder {
    /** @param options допустимые стеки по одной штуке, первый — исходный предмет шаблона */
    void ryscope$setAlternatives(Object[] options);
}
