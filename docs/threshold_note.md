# Threshold note

**Recommendation: block (malicious) at 0.85, warn (caution) from 0.60.** On the held-out
split (82,249 URLs, 16% phishing), the current balanced threshold of 0.758 maximises F1
(0.787) but wrongly flags 3.0% of legitimate URLs (2,093 of 68,960) as malicious. The app
turns that into a hard block, so a wrong call stops the user from reaching a legitimate
site. Raising the block threshold to 0.85 cuts the false-positive rate to 1.3% (precision
0.91) at the cost of recall, which falls from 75% to 65%. The 0.60 to 0.85 band is not
lost, because it shows the dismissible "caution" interstitial (with caution at 0.60, 85% of
phishing is at least warned about, and 6.6% of legitimate URLs get a warning or block). The current caution
cut-off of 0.45 warns on 11% of legitimate URLs, which is enough for users to start
ignoring warnings, so I would raise it. The sweep (`docs/holdout_metrics.json`) shows
F1 is nearly flat between 0.65 and 0.80, so little accuracy is lost by choosing the safer
end of that range. Two caveats: the dataset is 16% phishing, while real traffic is far
less, so real-world precision will be lower than shown at any threshold; and these
thresholds were chosen on the same split they were measured on, so treat the numbers as
slightly optimistic. The allowlist helps most here, since it short-circuits about 16% of
this test set (almost all legitimate URLs) before the model sees it.
