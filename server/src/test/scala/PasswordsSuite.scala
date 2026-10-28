package com.alecdorrington.hecate
package server

import cats.syntax.all.*
import munit.CatsEffectSuite

class PasswordsSuite extends CatsEffectSuite:

  private val iterations = 1000

  test("a password verifies against its hash alone, salted afresh each time"):
    for
      first <- Passwords.hash(
        "correct horse battery staple",
        iterations,
      )
      second <- Passwords.hash(
        "correct horse battery staple",
        iterations,
      )
      right <- Passwords.verify("correct horse battery staple", first)
      wrong <- Passwords.verify("Tr0ub4dor&3", first)
    yield
      assert(right, "the password did not verify")
      assert(!wrong, "a wrong password verified")
      assertNotEquals(first, second)

  test("a malformed stored hash never verifies"):
    List(
      "not a stored hash",
      "12:%%%:###",
      "120000::",
      "",
      // A count no thread should obey, and one no derivation can use.
      "999999999999:c2FsdHk=:aGFzaHk=",
      "0:c2FsdHk=:aGFzaHk=",
      "-1:c2FsdHk=:aGFzaHk=",
    ).traverse_(Passwords.verify("anything", _).assertEquals(false))

  test("no password verifies against the decoy, which costs a real derivation"):
    Passwords.decoy(iterations).split(':') match
      case Array(count, salt, hash) =>
        assertEquals(count.toIntOption, Some(iterations))
        assert(salt.nonEmpty)
        assert(hash.nonEmpty)
      case other => fail(s"The decoy is malformed: ${ other.mkString(":") }")
    Passwords
      .verify(
        "anything",
        Passwords.decoy(iterations),
      )
      .assertEquals(false)

  test("a hash derived under fewer iterations is the one marked outdated"):
    Passwords
      .hash(
        "correct horse battery staple",
        iterations,
      )
      .map: hash =>
        assert(Passwords.outdated(hash, iterations + 1))
        assert(!Passwords.outdated(hash, iterations))
        assert(!Passwords.outdated(hash, 1))
        assert(!Passwords.outdated("nonsense", iterations + 1))
