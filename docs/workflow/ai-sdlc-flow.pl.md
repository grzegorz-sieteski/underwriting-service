# Przepływ AI SDLC — opis (po polsku)

Proces składa się z ośmiu etapów. Każdy etap wykonuje osobna rola w świeżym kontekście, więc
żadna rola nie dziedziczy rozumowania poprzedniej. Bramka to warunek, który orkiestrator sprawdza,
zanim praca przejdzie dalej.

- **Etap 0, orkiestrator.** Prowadzi tablicę stanu w `docs/workflow/`. Kieruje pracą i sprawdza
  każdą bramkę; nie pisze kodu ani testów.
- **Etap 1, analityk biznesowy.** Pisze specyfikację w `docs/specs/` jako numerowane reguły
  biznesowe (`BR-xx`). Każdą liczbę biznesową, której nie da się przypisać do właściciela produktu,
  oznacza jako `ASSUMPTION`. **Bramka:** brak nierozstrzygniętych znaczników `ASSUMPTION`.
- **Etap 2, architekt.** Pisze projekt techniczny w `docs/adr/`. **Bramka:** projekt obejmuje
  każdą komendę ze specyfikacji.
- **Etap 3, test-designer (RED).** Wypisuje wszystkie scenariusze przepływów biznesowych w
  `docs/tests/`, każdy powiązany z regułą `BR-xx`, bez nazw klas. Potem pisze testy, które
  prowadzą przepływ wyłącznie przez kontrakt testowy z projektu (wejście use case'u, endpoint
  HTTP, porty do podmiany), oraz fake'i portów, wyłącznie w `src/test/`. Testy są znajdowane po
  identyfikatorze scenariusza na początku `@DisplayName`, nie po klasie i metodzie, więc nie
  narzucają struktury kodu. **Bramka:** każda reguła ma scenariusz, każdy NOWY scenariusz ma test,
  `src/main/` nietknięte. Testy jeszcze się nie kompilują, bo typów nie ma.
- **Etap 4a, programista (SZKIELET).** Tworzy kontrakt testowy z projektu, ciała to `return null;`.
  Szkielet nie jest commitowany. **Bramka:** każdy NOWY test **nie przechodzi**. Test zielony na
  szkielecie niczego nie sprawdza i wraca do etapu 3.
- **Etap 4b, programista (GREEN).** Implementuje `src/main/`, aż testy przejdą. Nie może zmieniać
  testów. **Bramka:** `verify` przechodzi, a `src/test/` jest nietknięte, co sprawdza
  `scripts/check-write-lock.sh green`.
- **Etap 5, tester QA.** Pisze scenariusze HTTP i testy adversarial. Nie może łatać kodu
  produkcyjnego. **Bramka:** `src/main/` jest nietknięte.
- **Etap 6, recenzenci kodu i bezpieczeństwa.** Tylko odczyt. Każdy zwraca `[APPROVED]` albo
  `[CHANGES REQUIRED]`.

Gdy recenzent zwróci `CHANGES REQUIRED`, praca wraca do **etapu 3**. Najpierw powstaje
nieprzechodzący test dla każdego znaleziska, dopiero potem zmiana kodu. Test, który nie pada przed
poprawką, nie dowodzi poprawki.

Reguły blokad zapisu egzekwuje `scripts/check-write-lock.sh`, który porównuje z ostatnim
zatwierdzonym punktem odniesienia, a nie z tym, co akurat jest w `HEAD`.
