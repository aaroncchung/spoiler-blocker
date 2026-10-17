# Evaluation script

Runs the matcher over a file of labelled examples and counts its two kinds of
mistake:

- A **miss** is an example labelled related that the matcher allowed. This is
  the costly mistake: a spoiler gets through.
- An **over-block** is an example labelled unrelated that the matcher blocked.

It is the tool for Phase 6 of [the build plan](../docs/BUILD_PLAN.md). It runs
on a PC and is not part of the app.

## Running it

From the repository root:

```
./gradlew -q :eval:run --args="eval/sample/blocker.json eval/sample/examples.jsonl"
```

The two file names are relative to the repository root. `-q` hides Gradle's
own output. The sample files are invented and only show the formats.

The output gives the totals, the number and rate of misses and of over-blocks,
then every miss, then every over-block with the rule that caused it. A rate
is reported as "not measured" when the file has no examples of that kind: no
related examples means nothing is known about misses.

## Where real data goes

Put real examples in `eval/data/`. Git ignores that folder.

**Never commit real examples.** Titles, captions and notifications copied from
a phone contain other people's names and messages, and this repository is
public. Do not add them to `eval/sample/` either.

## File formats

Both files must be saved as UTF-8. A file in another encoding is refused,
because its accented letters would be read wrongly and change the counts.

### Blocker file

One JSON object with the lists of one blocker:

```json
{
  "strong": ["Japanese Grand Prix", "Suzuka"],
  "weak": ["Max", "podium"],
  "sources": ["FORMULA 1"],
  "breadth": "narrow"
}
```

`breadth` is `"narrow"` or `"broad"`. Only `strong` is required. `weak` and
`sources` default to empty lists and `breadth` to `"narrow"`. Any other key,
or a key that is given twice, stops the script with an error.

### Examples file

One JSON object per line (the "JSON Lines" format), so an object cannot be
spread over several lines:

```json
{"label":"related","texts":["Rain at Suzuka","Highlights from Sunday"],"sources":["FORMULA 1"]}
{"kind":"notification","label":"unrelated","texts":["Dinner at 7?"],"sources":["WhatsApp","Family"],"note":"A message"}
```

| Key | Required | Meaning |
|---|---|---|
| `label` | Yes | `"related"` if the example is about the blocker's topic, otherwise `"unrelated"`. |
| `texts` | Yes | The separate pieces of text, for example a title and a caption, or the text of a notification. At least one must not be empty. |
| `sources` | No | Where it came from. For a post, the channel or account. For a notification, the app name, the title and the names of the people who sent its messages. |
| `kind` | No | `"post"`, which is the default, or `"notification"`. It decides where terms are looked for, see below. |
| `note` | No | Anything you want to remember. It is printed with the example and the matcher never sees it. |

Blank lines are skipped. Any other line that cannot be read stops the script
with an error that names the line, and so does a key that is not in the table
or a key that is given twice.

The matcher looks for the blocker's sources in `sources` only. Where it looks
for strong and weak terms depends on `kind`, and is the same as in the app:

- In a post, in `texts` only. A channel or account name is only looked for as
  a source.
- In a notification, in `texts` and in `sources`. The app looks for terms in
  a notification's app name, title and senders as well. So a WhatsApp message
  from Max saying "podium!!" holds the weak terms "Max" and "podium". There is
  no need to copy the title into `texts`.

Mark every notification with `"kind":"notification"`. Without it, a
notification is counted as a post: the script reports misses that the app
would not make and leaves out over-blocks that the app would make.
