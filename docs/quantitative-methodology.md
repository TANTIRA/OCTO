# Quantitative Methodology

Canonical equations for quantitative analysts, researchers, portfolio managers, and traders using Mesta-Asset. Every calculated result must retain its formula version, input lineage, valuation date, currency, and calculation convention.

## 1. Notation

| Symbol | Meaning |
| --- | --- |
| \(C_t\) | Signed cash flow at time \(t\); contributions are negative and distributions are positive |
| \(NAV_T\) | Residual net asset value at valuation date \(T\) |
| \(PIC_T\) | Paid-in capital through \(T\), expressed as a positive amount |
| \(D_T\) | Cumulative distributions through \(T\) |
| \(V_t\) | Asset or portfolio value at time \(t\) |
| \(r_t\) | Period return at time \(t\) |
| \(w_i\) | Portfolio weight of asset \(i\) |
| \(R_f\) | Risk-free return |
| \(R_m\) | Market return |
| \(B_t\) | Public-market benchmark index level at time \(t\) |
| \(d_t\) | Calendar date of cash flow \(C_t\) |
| \(\Delta t\) | Year fraction under the selected day-count convention |

## 2. Private-equity performance

### 2.1 Internal Rate of Return (IRR / XIRR)

For irregularly dated private-market cash flows, solve \(r\) numerically:

$$
0 = \sum_{t=0}^{n} \frac{C_t}{(1+r)^{(d_t-d_0)/365}} + \frac{NAV_T}{(1+r)^{(d_T-d_0)/365}}
$$

Use the actual dates from the IBOR. Report Gross IRR before management fees, carried interest, and fund expenses; report Net IRR after these items. A result is undefined when the cash-flow pattern does not admit a unique economically meaningful root.

### 2.2 Multiples

$$
DPI_T = \frac{D_T}{PIC_T}
$$

$$
RVPI_T = \frac{NAV_T}{PIC_T}
$$

$$
TVPI_T = DPI_T + RVPI_T = \frac{D_T + NAV_T}{PIC_T}
$$

$$
MOIC_T = \frac{\text{Realized Value}_T + \text{Unrealized Value}_T}{\text{Invested Capital}_T}
$$

TVPI uses paid-in capital at fund/LP level. MOIC uses invested capital at the deal level. The UI must not silently treat them as interchangeable.

### 2.3 Commitment and drawdown

$$
UnfundedCommitment_T = CommittedCapital - CalledCapital_T
$$

$$
DrawdownRate_T = \frac{CalledCapital_T}{CommittedCapital}
$$

$$
DistributionRate_T = \frac{D_T}{CalledCapital_T}
$$

### 2.4 Public Market Equivalent (Kaplan-Schoar PME)

Scale each cash flow by benchmark growth to valuation date \(T\):

$$
KS\text{-}PME =
\frac{\sum_{t \in distributions} C_t \frac{B_T}{B_t} + NAV_T}
{\sum_{t \in contributions} |C_t| \frac{B_T}{B_t}}
$$

A value above 1 indicates outperformance relative to the benchmark under the KS-PME convention.

### 2.5 Direct Alpha

Convert cash flows into benchmark-relative cash flows:

$$
C_t^* = C_t \frac{B_T}{B_t}
$$

Direct Alpha is the IRR of \(C_t^*\) plus terminal \(NAV_T\). It expresses annualized private-market performance relative to the selected public benchmark.

## 3. Returns and risk

### 3.1 Time-weighted return

For subperiods separated by external cash flows:

$$
TWR = \prod_{t=1}^{n}(1+r_t)-1
$$

$$
r_t = \frac{V_t - V_{t-1} - NetFlow_t}{V_{t-1}}
$$

Use TWR for manager performance when the manager does not control external cash-flow timing. Use IRR for investor experience when timing matters.

### 3.2 Portfolio return and volatility

$$
R_p = \sum_{i=1}^{N} w_i R_i
$$

$$
\sigma_p = \sqrt{\mathbf{w}^{\mathsf T}\Sigma\mathbf{w}}
$$

where \(\Sigma\) is the asset return covariance matrix.

### 3.3 Sharpe, Sortino, and maximum drawdown

$$
Sharpe = \frac{E[R_p-R_f]}{\sigma_p}
$$

$$
Sortino = \frac{E[R_p-R_{target}]}{\sqrt{E[\min(R_p-R_{target},0)^2]}}
$$

$$
MDD = \max_t\left(\frac{Peak_t - V_t}{Peak_t}\right), \qquad Peak_t=\max_{u\le t}V_u
$$

Private-asset NAV smoothing can understate volatility and drawdown. Label risk measures based on appraisal valuations accordingly.

### 3.4 Value at Risk and Expected Shortfall

Parametric normal VaR at confidence \(\alpha\):

$$
VaR_{\alpha,h} = V_0\left(z_{\alpha}\sigma\sqrt{h}-\mu h\right)
$$

Historical Expected Shortfall:

$$
ES_\alpha = -E[R_p \mid R_p \le q_{1-\alpha}]
$$

For illiquid assets, supplement VaR with scenario analysis, liquidity horizons, stale-price adjustments, and unfunded-commitment stress tests.

## 4. Factor and attribution analysis

### 4.1 CAPM and multifactor model

$$
R_i-R_f = \alpha_i + \beta_i(R_m-R_f)+\epsilon_i
$$

$$
\boldsymbol{r}_t = \boldsymbol{\alpha} + B\boldsymbol{f}_t + \boldsymbol{\epsilon}_t
$$

Estimate factor exposure using a declared window, frequency, benchmark, and robust standard-error method.

### 4.2 Brinson attribution

For sector \(s\), portfolio weight \(w_{p,s}\), benchmark weight \(w_{b,s}\), portfolio return \(r_{p,s}\), and benchmark return \(r_{b,s}\):

$$
Allocation_s=(w_{p,s}-w_{b,s})(r_{b,s}-r_b)
$$

$$
Selection_s=w_{b,s}(r_{p,s}-r_{b,s})
$$

$$
Interaction_s=(w_{p,s}-w_{b,s})(r_{p,s}-r_{b,s})
$$

### 4.3 Private-equity value-creation bridge

For enterprise value \(EV = Revenue \times Multiple\):

$$
\Delta EV = EV_{exit}-EV_{entry}
$$

A sequential bridge can attribute value to revenue growth, margin improvement, multiple change, debt paydown, FX, and other effects. Because sequential attribution is order-dependent, the engine must store the chosen ordering. For order-neutral attribution, use Shapley values:

$$
\phi_i = \sum_{S\subseteq N\setminus\{i\}}
\frac{|S|!(|N|-|S|-1)!}{|N|!}\left[v(S\cup\{i\})-v(S)\right]
$$

## 5. Valuation

### 5.1 Discounted Cash Flow

$$
EV = \sum_{t=1}^{N}\frac{FCFF_t}{(1+WACC)^t} + \frac{TV_N}{(1+WACC)^N}
$$

$$
TV_N = \frac{FCFF_{N+1}}{WACC-g}
$$

$$
EquityValue = EV - NetDebt - PreferredClaims + NonOperatingAssets
$$

### 5.2 Weighted Average Cost of Capital

$$
WACC = \frac{E}{D+E}R_e + \frac{D}{D+E}R_d(1-T_c)
$$

$$
R_e = R_f + \beta_L(R_m-R_f) + RP_{size} + RP_{country} + RP_{specific}
$$

### 5.3 Comparable-company valuation

$$
ImpliedEV_i = Metric_{company}\times Multiple_{peer,i}
$$

Use median and percentile ranges rather than a single peer multiple. Store peer set, metric period, outlier policy, and adjustments with each valuation run.

### 5.4 Leveraged buyout return

$$
ExitEV = ExitMetric \times ExitMultiple
$$

$$
ExitEquity = ExitEV - ExitNetDebt
$$

$$
SponsorMOIC = \frac{ExitEquity + InterimDistributions}{InitialSponsorEquity}
$$

Solve the dated cash-flow equation in section 2.1 for Sponsor IRR.

## 6. Forecasting and research

### 6.1 Growth and margins

$$
Growth_t = \frac{X_t}{X_{t-1}}-1
$$

$$
CAGR = \left(\frac{X_T}{X_0}\right)^{1/n}-1
$$

$$
EBITDA\ Margin = \frac{EBITDA}{Revenue}
$$

$$
FCF = EBIT(1-T_c)+D\&A-Capex-\Delta NWC
$$

### 6.2 Weighted forecast and error

$$
\hat y_{t+h}=\sum_{k=1}^{K}\omega_k\hat y_{k,t+h}, \qquad \sum_k\omega_k=1
$$

$$
MAE=\frac{1}{n}\sum_{t=1}^{n}|y_t-\hat y_t|
$$

$$
RMSE=\sqrt{\frac{1}{n}\sum_{t=1}^{n}(y_t-\hat y_t)^2}
$$

Use walk-forward validation. Never select a model using future observations or revised data unavailable at prediction time.

### 6.3 Bayesian update

For hypothesis \(H\) and evidence \(E\):

$$
P(H\mid E)=\frac{P(E\mid H)P(H)}{P(E)}
$$

Store the prior, evidence timestamp, data vintage, posterior, and analyst override.

## 7. Portfolio construction and liquidity

### 7.1 Mean-variance optimization

$$
\min_{\mathbf w}\ \frac{1}{2}\mathbf w^{\mathsf T}\Sigma\mathbf w-\lambda\boldsymbol{\mu}^{\mathsf T}\mathbf w
$$

subject to:

$$
\mathbf 1^{\mathsf T}\mathbf w=1,\quad \mathbf w_{min}\le\mathbf w\le\mathbf w_{max}
$$

Additional constraints may cover sector, geography, vintage, manager, currency, concentration, and liquidity.

### 7.2 Look-through exposure

For ownership path \(p\) from investor to underlying company:

$$
Exposure_{company} = \sum_{p}\left(\prod_{e\in p}Ownership_e\right)\times NAV_{root,p}
$$

Sum all valid paths to prevent undercounting when exposure reaches the same company through multiple funds or vehicles.

### 7.3 Unfunded-commitment coverage

$$
CoverageRatio = \frac{LiquidAssets + ForecastInflows}{UnfundedCommitments + ForecastOutflows}
$$

Stress by accelerating capital calls, delaying distributions, applying FX shocks, and reducing liquid-asset values.

## 8. Trade and execution analytics

These equations apply when the platform supports listed securities, secondary transactions, FX hedges, or trade execution related to private-market operations.

### 8.1 Profit and loss

$$
RealizedPnL = (P_{sell}-P_{buy})Q - Fees - Taxes
$$

$$
UnrealizedPnL_t = (P_t-P_{cost})Q_t
$$

$$
TotalPnL = RealizedPnL + UnrealizedPnL + Income + FXPnL
$$

### 8.2 Execution quality

$$
Slippage = Side\times(P_{exec}-P_{arrival})Q
$$

where \(Side=+1\) for buys and \(-1\) for sells, so positive slippage is a cost.

$$
VWAP = \frac{\sum_i P_iQ_i}{\sum_iQ_i}
$$

$$
ImplementationShortfall = Side\times(P_{exec}-P_{decision})Q + Fees + OpportunityCost
$$

### 8.3 Position sizing

For maximum tolerated loss \(L\), entry price \(P_e\), and stop price \(P_s\):

$$
Q = \frac{L}{|P_e-P_s|+EstimatedCostPerUnit}
$$

All trade analytics must identify the price source, timestamp, venue, currency, FX rate, and fee assumptions.

## 9. State-space and regime models

Use these models for latent market regimes, portfolio-company operating states, valuation smoothing, nowcasting, volatility forecasting, and anomaly detection. Model output is probabilistic evidence—not an autonomous trading or valuation decision.

### 9.1 Hidden Markov Model

Let the unobserved state be \(S_t\in\{1,\ldots,K\}\), the observation be \(y_t\), transition matrix be \(A\), initial state probabilities be \(\boldsymbol\pi\), and state-conditional emission parameters be \(\theta_k\):

$$
P(S_t=j\mid S_{t-1}=i)=a_{ij}, \qquad \sum_{j=1}^{K}a_{ij}=1
$$

$$
P(S_1=i)=\pi_i, \qquad p(y_t\mid S_t=k)=f(y_t;\theta_k)
$$

For Gaussian emissions:

$$
y_t\mid S_t=k \sim \mathcal N(\mu_k,\Sigma_k)
$$

The forward recursion computes the filtered joint probability:

$$
\alpha_1(j)=\pi_j f(y_1;\theta_j)
$$

$$
\alpha_t(j)=f(y_t;\theta_j)\sum_{i=1}^{K}\alpha_{t-1}(i)a_{ij}
$$

Normalize at each step to prevent numerical underflow. The filtered state probability is:

$$
P(S_t=j\mid y_{1:t})=\frac{\alpha_t(j)}{\sum_k\alpha_t(k)}
$$

The backward recursion is:

$$
\beta_T(i)=1
$$

$$
\beta_t(i)=\sum_{j=1}^{K}a_{ij}f(y_{t+1};\theta_j)\beta_{t+1}(j)
$$

The smoothed state probability, which uses the full sample, is:

$$
P(S_t=i\mid y_{1:T})=\frac{\alpha_t(i)\beta_t(i)}{\sum_j\alpha_t(j)\beta_t(j)}
$$

Use filtered probabilities in live decisions. Smoothed probabilities use future observations and are allowed only for historical analysis, labeling, and parameter estimation.

The most likely state sequence follows the Viterbi recursion:

$$
\delta_1(j)=\log\pi_j+\log f(y_1;\theta_j)
$$

$$
\delta_t(j)=\max_i\left[\delta_{t-1}(i)+\log a_{ij}\right]+\log f(y_t;\theta_j)
$$

Estimate parameters with maximum likelihood (Baum-Welch/EM) or Bayesian inference. Select \(K\) using out-of-sample likelihood, stability, interpretability, and information criteria—not in-sample fit alone.

Expected state duration is:

$$
E[D_i]=\frac{1}{1-a_{ii}}
$$

### 9.2 Markov regime-switching model

A regime-switching autoregression allows return, growth, spread, or volatility dynamics to change with latent state \(S_t\):

$$
y_t=\mu_{S_t}+\sum_{p=1}^{P}\phi_{p,S_t}y_{t-p}+\sigma_{S_t}\epsilon_t,
\qquad \epsilon_t\sim\mathcal N(0,1)
$$

A two-state return model might represent expansion/risk-on and contraction/risk-off:

$$
r_t\mid S_t=k \sim \mathcal N(\mu_k,\sigma_k^2), \qquad k\in\{1,2\}
$$

The one-step predicted regime probability is:

$$
P(S_{t+1}=j\mid y_{1:t})=\sum_i a_{ij}P(S_t=i\mid y_{1:t})
$$

The probability-weighted forecast is:

$$
E[y_{t+1}\mid y_{1:t}]=\sum_{j=1}^{K}P(S_{t+1}=j\mid y_{1:t})E[y_{t+1}\mid S_{t+1}=j]
$$

For time-varying transition probabilities driven by covariates \(z_t\):

$$
P(S_t=j\mid S_{t-1}=i,z_t)=
\frac{\exp(\gamma_{ij}^{\mathsf T}z_t)}{\sum_{\ell=1}^{K}\exp(\gamma_{i\ell}^{\mathsf T}z_t)}
$$

Do not hard-switch a portfolio at an arbitrary probability threshold without testing turnover, transaction costs, hysteresis, and minimum-state-duration rules. Prefer probability-weighted exposure when the mandate permits it.

A generic probability-weighted target allocation is:

$$
\mathbf w_t^*=\sum_{k=1}^{K}P(S_t=k\mid y_{1:t})\mathbf w_k
$$

A hysteresis policy can enter regime \(k\) at threshold \(h_{enter}\) and exit only below \(h_{exit}\), where \(h_{exit}<h_{enter}\), reducing unstable switching.

### 9.3 Linear Kalman filter

Define the latent state \(\mathbf x_t\), control input \(\mathbf u_t\), and observed measurement \(\mathbf y_t\):

$$
\mathbf x_t=F_t\mathbf x_{t-1}+B_t\mathbf u_t+\mathbf w_t,
\qquad \mathbf w_t\sim\mathcal N(0,Q_t)
$$

$$
\mathbf y_t=H_t\mathbf x_t+\mathbf v_t,
\qquad \mathbf v_t\sim\mathcal N(0,R_t)
$$

Prediction:

$$
\hat{\mathbf x}_{t\mid t-1}=F_t\hat{\mathbf x}_{t-1\mid t-1}+B_t\mathbf u_t
$$

$$
P_{t\mid t-1}=F_tP_{t-1\mid t-1}F_t^{\mathsf T}+Q_t
$$

Innovation and innovation covariance:

$$
\tilde{\mathbf y}_t=\mathbf y_t-H_t\hat{\mathbf x}_{t\mid t-1}
$$

$$
S_t=H_tP_{t\mid t-1}H_t^{\mathsf T}+R_t
$$

Update:

$$
K_t=P_{t\mid t-1}H_t^{\mathsf T}S_t^{-1}
$$

$$
\hat{\mathbf x}_{t\mid t}=\hat{\mathbf x}_{t\mid t-1}+K_t\tilde{\mathbf y}_t
$$

$$
P_{t\mid t}=(I-K_tH_t)P_{t\mid t-1}
$$

Use the Joseph covariance update for improved numerical stability:

$$
P_{t\mid t}=(I-K_tH_t)P_{t\mid t-1}(I-K_tH_t)^{\mathsf T}+K_tR_tK_t^{\mathsf T}
$$

For historical smoothing, the Rauch-Tung-Striebel backward pass is:

$$
J_t=P_{t\mid t}F_{t+1}^{\mathsf T}P_{t+1\mid t}^{-1}
$$

$$
\hat{\mathbf x}_{t\mid T}=\hat{\mathbf x}_{t\mid t}+J_t(\hat{\mathbf x}_{t+1\mid T}-\hat{\mathbf x}_{t+1\mid t})
$$

As with HMM smoothing, the RTS result uses future data and is not valid as a live point-in-time signal.

### 9.4 Dynamic regression and pairs/spread model

A time-varying hedge ratio can be modeled as:

$$
y_t=\alpha_t+\beta_t x_t+v_t
$$

$$
\begin{bmatrix}\alpha_t\\\beta_t\end{bmatrix}
=
\begin{bmatrix}\alpha_{t-1}\\\beta_{t-1}\end{bmatrix}
+\mathbf w_t
$$

The filtered residual is:

$$
z_t=y_t-\hat\alpha_{t\mid t-1}-\hat\beta_{t\mid t-1}x_t
$$

Normalize by innovation uncertainty rather than a fixed rolling standard deviation:

$$
Z_t=\frac{z_t}{\sqrt{S_t}}
$$

Before treating this as a trade signal, test economic linkage, residual stationarity, borrow availability, execution costs, structural breaks, and out-of-sample decay.

### 9.5 Nonlinear and non-Gaussian extensions

- **Extended Kalman Filter (EKF):** linearizes nonlinear transition and measurement functions using Jacobians.
- **Unscented Kalman Filter (UKF):** propagates sigma points and avoids explicit Jacobians.
- **Particle Filter:** approximates arbitrary filtering distributions with weighted particles; appropriate for strongly nonlinear or non-Gaussian systems.
- **Switching Kalman Filter:** combines discrete regimes with continuous latent states; use when both state dynamics and regime parameters change.

Use the simplest model that survives out-of-sample validation. Complexity requires a measurable improvement after costs and uncertainty.

### 9.6 Platform outputs and governance

Each model run must store:

- model family and immutable version;
- state definitions and analyst interpretation;
- feature set, transformations, frequency, and data vintage;
- training and validation windows;
- transition matrix and expected state durations;
- filtered probabilities available at decision time;
- smoothed probabilities, clearly labeled historical-only;
- parameter uncertainty and diagnostics;
- active benchmark or challenger status;
- downstream decisions, overrides, and realized outcomes.

Required diagnostics include log likelihood, out-of-sample predictive likelihood, residual autocorrelation, standardized-innovation tests, probability calibration, parameter stability, state occupancy, turnover, and performance after fees and slippage.

## 10. Data and calculation controls

1. **Use dated cash flows.** Never substitute period-end approximations when transaction dates exist.
2. **Preserve signs.** Contributions/calls are negative; distributions and terminal NAV are positive for investor-return calculations.
3. **Declare conventions.** Currency, FX source, day count, annualization, valuation policy, benchmark, fee treatment, and gross/net basis are mandatory metadata.
4. **Avoid aggregation bias.** Compute portfolio metrics from underlying cash flows; do not average fund IRRs.
5. **Version formulas.** Every metric definition is immutable after publication; corrections create a new version.
6. **Expose lineage.** UI results link to formulas, input entities, ledger events, and source records.
7. **Handle missing values explicitly.** Never convert missing observations to zero unless the domain definition requires zero.
8. **Backtest without leakage.** Use point-in-time data and walk-forward splits.
9. **Quantify uncertainty.** Forecasts and valuations include scenarios or confidence intervals, not only point estimates.
10. **Require review.** New or materially changed financial metrics are T2 and require independent quantitative validation.
