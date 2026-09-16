# AnoNe Plugin

It provides IntelliJ IDEA support for AnoNe.

Supported annotations are as follows:
- `GeneratedAccess`, `ReflectiveAccess`, `GeneratedInvocation`, `ReflectiveInvocation`
  - Suppresses unused warnings
- `CanIgnoreReturnValue`
  - Suppresses unused return value warnings
  - Works together with any third-party `CheckReturnValue`
- `MustNotClose` 
  - Highlights contract violations
  - Tracks borrowed resources through code branches (shallow data flow analysis)
- `MustCallAt` & `MustNotCallAt`
  - Highlights contract violations & invalid annotation usages
- `OverrideOnly` & `InvokeOnly`
  - Highlights contract violations
  - Warns `OverrideOnly` bypasses
