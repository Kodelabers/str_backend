package com.str.backend.email;

final class EmailTemplates {

    private EmailTemplates() {
    }

    static String approvalBody(String firstName, String username, String loginUrl) {
        String safeName = escape(firstName);
        String safeUsername = escape(username);
        String safeLoginUrl = escape(loginUrl);
        return wrap(
                """
                <h2 style="color:#168ABF;margin:0 0 12px;">Registracija je odobrena</h2>
                <p>Poštovani/a %s,</p>
                <p>Vaš zahtjev za registraciju u sustav eTurizam STR — Kratkoročni najam je <strong>odobren</strong>.
                Sada se možete prijaviti svojim korisničkim imenom: <strong>%s</strong>.</p>
                %s

                <hr style="border:none;border-top:1px solid #DEE2E6;margin:24px 0;">

                <h2 style="color:#168ABF;margin:0 0 12px;">Registration approved</h2>
                <p>Dear %s,</p>
                <p>Your registration request in the eTurizam STR — Short-term rental system has been
                <strong>approved</strong>. You can now log in using your username: <strong>%s</strong>.</p>
                %s
                """.formatted(
                        safeName, safeUsername, button("Prijava", safeLoginUrl),
                        safeName, safeUsername, button("Log in", safeLoginUrl)
                )
        );
    }

    static String rnIssuedBody(String firstName, String registrationNumber) {
        String safeName = escape(firstName);
        String safeRn = escape(registrationNumber);
        return wrap(
                """
                <h2 style="color:#168ABF;margin:0 0 12px;">Registracijski broj je izdan</h2>
                <p>Poštovani/a %s,</p>
                <p>Vaš zahtjev za registraciju smještajne jedinice kratkoročnog najma je obrađen.
                Izdani registracijski broj je: <strong>%s</strong>.</p>
                <p>U privitku se nalazi PDF zahtjeva s izdanim registracijskim brojem.</p>

                <hr style="border:none;border-top:1px solid #DEE2E6;margin:24px 0;">

                <h2 style="color:#168ABF;margin:0 0 12px;">Registration number issued</h2>
                <p>Dear %s,</p>
                <p>Your short-term rental registration request has been processed.
                The issued registration number is: <strong>%s</strong>.</p>
                <p>The submission PDF with the issued registration number is attached.</p>
                """.formatted(safeName, safeRn, safeName, safeRn)
        );
    }

    static String rejectionBody(String firstName) {
        String safeName = escape(firstName);
        return wrap(
                """
                <h2 style="color:#B71C1C;margin:0 0 12px;">Registracija nije prihvaćena</h2>
                <p>Poštovani/a %s,</p>
                <p>Nažalost, Vaš zahtjev za registraciju u sustav eTurizam STR — Kratkoročni najam
                nije prihvaćen. Provjerite ispravnost dostavljenih podataka i pokušajte ponovno,
                ili kontaktirajte podršku za više informacija.</p>

                <hr style="border:none;border-top:1px solid #DEE2E6;margin:24px 0;">

                <h2 style="color:#B71C1C;margin:0 0 12px;">Registration not approved</h2>
                <p>Dear %s,</p>
                <p>Unfortunately, your registration request for the eTurizam STR — Short-term rental
                system has not been approved. Please verify the submitted information and try again,
                or contact support for more information.</p>
                """.formatted(safeName, safeName)
        );
    }

    private static String wrap(String inner) {
        return """
                <!doctype html>
                <html><head><meta charset="utf-8"></head>
                <body style="margin:0;padding:24px;background-color:#F8F9FA;font-family:Arial,sans-serif;color:#212529;line-height:1.5;">
                  <div style="max-width:560px;margin:0 auto;background:#ffffff;border:1px solid #DEE2E6;padding:32px;">
                    %s
                    <p style="margin-top:32px;color:#6C757D;font-size:12px;">
                      Ovo je automatska poruka — molimo ne odgovarajte. ·
                      This is an automated message — please do not reply.
                    </p>
                  </div>
                </body></html>
                """.formatted(inner);
    }

    private static String button(String label, String url) {
        return """
                <p style="margin:24px 0;">
                  <a href="%s" style="display:inline-block;padding:12px 24px;background:#168ABF;color:#ffffff;text-decoration:none;font-weight:600;">%s</a>
                </p>
                """.formatted(url, escape(label));
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
