# PhishGuard: viva questions and answers

**Q1. Why use only the URL string? Why not fetch the page?**
It is fast (about 2 ms per URL), needs no network access, never makes the
server visit a possibly dangerous page, and guarantees that training and live
inference compute exactly the same features. The cost is a ceiling on accuracy,
because a URL can't show what the page does.

**Q2. Why LightGBM and not a neural network?**
The data is tabular: 27 numeric features. Gradient-boosted trees are strong on
tabular data, train in seconds, need no feature scaling, and support exact,
fast SHAP explanations through TreeSHAP. A character-level deep model such as
URLNet could be explored, but it would be slower and harder to explain.

**Q3. What is SHAP, in one sentence?**
For one prediction, SHAP splits the difference between the model's output and
its average output into a contribution from each feature, using Shapley values
from cooperative game theory, so the contributions add up exactly.

**Q4. How do the "why" bars work?**
We compute SHAP values for that single URL, keep only the positive ones (the
features pushing toward phishing), take the top 5, and scale them so the largest
is 1.0. Each feature name is mapped to a plain-English phrase.

**Q5. Your accuracy is 93%. Is that good?**
Accuracy is misleading here: calling every URL "safe" would already score about
84%, because only 16% are phishing. That's why we report ROC-AUC (0.9629),
PR-AUC (0.8817), precision, recall and false-positive rate.

**Q6. Why is the default threshold 0.758?**
It maximizes F1 on the test set, which is the best balance of precision (0.827)
and recall (0.751). Scores between 0.45 and 0.758 get "caution" rather than a
block. For deployment we recommend 0.85, which brings the false-positive rate
down to about 1.3%, because blocking a legitimate site costs user trust.

**Q7. Are your results optimistic in any way?**
Yes, in two ways. The thresholds were chosen on the same test split we report
on, and the dataset is 16% phishing, far more than real traffic, so real-world
precision would be lower. ROC-AUC and PR-AUC are not affected by threshold
choice.

**Q8. What does the model actually rely on?**
Mostly URL structure: path length, number of dots, hyphens and slashes, and the
longest word-like token. `brand_not_in_domain` contributes about 2% of gain.
`no_https` contributes nothing, because most URLs in this dataset have no
scheme, which is a dataset artifact.

**Q9. Why have an allowlist at all, if you have a model?**
It is instant, it removes false positives on the most-visited sites, and it
skips the model for about 15.6% of the test set. The downside is that it trusts
whole domains: 81 phishing URLs in the test set sat on trusted domains and would
have been marked safe.

**Q10. How do you stop the training and serving code from drifting apart?**
There is one `features.py`, written with only the standard library, which is
copied into the backend. A unit test fails if the two copies ever differ. The
model bundle also stores the feature order, and the evaluation script asserts
that it matches.

**Q11. What happens if the URL is malformed?**
Parsing is wrapped in error handling, and a missing scheme gets `http://`.
Unit tests cover `http://[::1/broken`, invalid ports, IP hosts, punycode, an
empty string and a 5,000-character URL. All of them return the full 27-feature
vector.

**Q12. What about privacy? You send every URL to a server.**
In the prototype, yes, to a backend the project team runs, and it never fetches
the page. The design goal is to run the model on the device, since it's small
and fast, and to use hash-prefix lookups for blocklists, as Google Safe
Browsing does, so the server can't tell which URL was checked.

**Q13. How does the in-app browser block a page before it loads?**
`shouldOverrideUrlLoading` can't wait for a network call, so it cancels the
navigation, checks the URL in a coroutine, and only then calls `loadUrl` if the
URL is safe. A malicious destination shows the report instead. `onPageStarted`
catches navigations that skip the first hook, such as back/forward or form posts.

**Q14. What is the camera hook for?**
Some malicious pages ask for the camera or microphone as soon as they load. A
script injected at page start detects `getUserMedia` calls and whether the user
had interacted first, and adds "Requests camera on load" as a risk factor.
Native permission prompts are denied by default.

**Q15. Did you create any phishing pages to test this?**
No. The project is detection-only and uses a public labeled dataset. The only
test page is a clearly labeled "TEST PAGE — not a real site" with no branding,
used to trigger the camera detector.

**Q16. What would you do next?**
Move scoring on-device with TFLite, fill the blocklist from a live feed, add
page-content and visual-similarity signals, re-evaluate on fresh,
time-separated data with realistic phishing rates, and add device-wide
protection through a VPN or accessibility service.
