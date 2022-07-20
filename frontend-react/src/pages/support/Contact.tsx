import { Helmet } from "react-helmet";
import { Link } from "react-router-dom";
import DOMPurify from "dompurify";

import site from "../../content/site.json";

export const Contact = () => {
    return (
        <>
            <Helmet>
                <title>Contact | Support | {process.env.REACT_APP_TITLE}</title>
            </Helmet>

            <h1>Contact us</h1>
            <h2>
                Want to get in touch with ReportStream? Email us at{" "}
                <a
                    href={"mailto:" + DOMPurify.sanitize(site.orgs.RS.email)}
                    className="usa-link"
                >
                    {site.orgs.RS.email}
                </a>
                .
            </h2>
            <section>
                <h3>Interested in partnering with ReportStream?</h3>
                <p>
                    If you want to learn more about ReportStream or how to get
                    started, email us at{" "}
                    <a
                        href={
                            "mailto:" + DOMPurify.sanitize(site.orgs.RS.email)
                        }
                        className="usa-link"
                    >
                        {site.orgs.RS.email}
                    </a>
                    .
                </p>
                <p>
                    To begin the account registration process, follow the steps
                    outlined in our{" "}
                    <Link
                        to="/resources/account-registration-guide"
                        key="account registration guide"
                        className="usa-link"
                    >
                        account registration guide
                    </Link>
                    .
                </p>
            </section>
            <section>
                {" "}
                <h3>
                    Have a problem with a data you're trying to send or receive?
                </h3>
                <p>
                    Read our guides for sending data{" "}
                    <a className="usa-link" href="/resources/elr-checklist">
                        via API (ELR)
                    </a>{" "}
                    or{" "}
                    <a className="usa-link" href="/resources/csv-upload-guide">
                        via CSV upload
                    </a>
                    . For all other issues, contact us at{" "}
                    <a
                        href={
                            "mailto:" + DOMPurify.sanitize(site.orgs.RS.email)
                        }
                        className="usa-link"
                    >
                        {site.orgs.RS.email}
                    </a>
                    .
                </p>
            </section>
            <section>
                <h3 className="margin-top-4">Need something else?</h3>
                <p>
                    For quick answers to common questions, read our{" "}
                    <a className="usa-link" href="/support/faq">
                        frequently asked questions
                    </a>
                    .
                </p>
                <p>
                    If you have another question, issue, or bug to report, we're
                    happy to help. Email us at{" "}
                    <a
                        href={
                            "mailto:" + DOMPurify.sanitize(site.orgs.RS.email)
                        }
                        className="usa-link"
                    >
                        {site.orgs.RS.email}
                    </a>
                    .
                </p>
            </section>
        </>
    );
};
