import { test } from "node:test";
import assert from "node:assert/strict";
import { decode, parseJUnit, summarize } from "../junit.mjs";

test("Gradle の形式：クラスごとの testsuite、failure の本文、skipped", () => {
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.example.FooTest" tests="3" skipped="1" failures="1" errors="0" time="0.5">
  <testcase name="ok()" classname="com.example.FooTest" time="0.1"/>
  <testcase name="ng()" classname="com.example.FooTest" time="0.2">
    <failure message="expected: &lt;1&gt; but was: &lt;2&gt;" type="org.opentest4j.AssertionFailedError">org.opentest4j.AssertionFailedError: expected: &lt;1&gt;
	at com.example.FooTest.ng(FooTest.java:12)</failure>
  </testcase>
  <testcase name="later()" classname="com.example.FooTest" time="0.0"><skipped/></testcase>
  <system-out><![CDATA[log <b>]]></system-out>
</testsuite>`;
  const cases = parseJUnit(xml);
  assert.equal(cases.length, 3);
  assert.deepEqual(cases.map((c) => c.status), ["passed", "failed", "skipped"]);
  assert.equal(cases[1].message, "expected: <1> but was: <2>");
  assert.match(cases[1].detail, /FooTest\.java:12/);
  assert.equal(cases[0].suite, "com.example.FooTest");
  assert.deepEqual(summarize(cases), { total: 3, passed: 1, failed: 1, skipped: 1, time: 0.3 });
});

test("node --test の形式：testsuites 直下の testcase と、describe の入れ子", () => {
  const xml = `<testsuites>
	<testcase name="トークンを発行する" time="0.07" classname="test"/>
	<testsuite name="検証" time="0.01">
		<testcase name="改ざんは 401" time="0.004" classname="test">
			<failure type="testCodeFailure" message="Expected values to be strictly equal:&#xA;401 !== 200">
[Error [ERR_TEST_FAILURE]: x]
			</failure>
		</testcase>
		<testcase name="あとで" time="0" classname="test"><skipped type="skipped" message="todo"/></testcase>
	</testsuite>
	<!-- tests 3 -->
</testsuites>`;
  const cases = parseJUnit(xml);
  assert.equal(cases.length, 3);
  assert.equal(cases[0].suite, "");
  assert.equal(cases[1].suite, "検証");
  assert.equal(cases[1].status, "failed");
  assert.equal(cases[1].message, "Expected values to be strictly equal:\n401 !== 200");
  assert.equal(cases[2].status, "skipped");
});

test("Vitest の形式：ファイル名の testsuite と、describe を含むテスト名", () => {
  const xml = `<testsuites name="vitest tests" tests="2" failures="1">
  <testsuite name="__tests__/app.test.tsx" tests="2">
    <testcase classname="__tests__/app.test.tsx" name="App &gt; 描画する" time="0.01"></testcase>
    <testcase classname="__tests__/app.test.tsx" name="App &gt; 送信する" time="0.02">
      <failure message="expected 1 to be 2" type="AssertionError">AssertionError: expected 1 to be 2</failure>
    </testcase>
  </testsuite>
</testsuites>`;
  const cases = parseJUnit(xml);
  assert.equal(cases[0].name, "App > 描画する");
  assert.equal(cases[1].classname, "__tests__/app.test.tsx");
  assert.equal(cases[1].status, "failed");
});

test("文字参照の展開", () => {
  assert.equal(decode("&lt;a&gt; &amp; &quot;&#39;&#x3042;&unknown;"), `<a> & "'あ&unknown;`);
});
