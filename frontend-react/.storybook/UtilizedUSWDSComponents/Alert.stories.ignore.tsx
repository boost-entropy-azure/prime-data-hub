import React, { ReactElement } from "react";
import { Meta } from "@storybook/react";
import { Alert, Button } from "@trussworks/react-uswds";

export default {
    title: "Components/Alert",
    component: Alert,
};

const testText = (
    <>
        Lorem ipsum dolor sit amet, <a href="#test">consectetur adipiscing</a>{" "}
        elit, sed do eiusmod.
    </>
);

export const Success = (): ReactElement => (
    <Alert type="success" heading="Success status" headingLevel="h4">
        {testText}
    </Alert>
);

export const Warning = (): ReactElement => (
    <Alert type="warning" heading="Warning status" headingLevel="h4">
        {testText}
    </Alert>
);

export const Error = (): ReactElement => (
    <Alert type="error" heading="Error status" headingLevel="h4">
        {testText}
    </Alert>
);

export const Info = (): ReactElement => (
    <Alert type="info" heading="Informative status" headingLevel="h4">
        {testText}
    </Alert>
);

export const Slim = (): ReactElement => (
    <>
        <Alert type="success" headingLevel="h4" slim>
            {testText}
        </Alert>
        <Alert type="warning" headingLevel="h4" slim>
            {testText}
        </Alert>
        <Alert type="error" headingLevel="h4" slim>
            {testText}
        </Alert>
        <Alert type="info" headingLevel="h4" slim>
            {testText}
        </Alert>
    </>
);

export const NoIcon = (): ReactElement => (
    <>
        <Alert type="success" headingLevel="h4" noIcon>
            {testText}
        </Alert>
        <Alert type="warning" headingLevel="h4" noIcon>
            {testText}
        </Alert>
        <Alert type="error" headingLevel="h4" noIcon>
            {testText}
        </Alert>
        <Alert type="info" headingLevel="h4" noIcon>
            {testText}
        </Alert>
    </>
);

export const SlimNoIcon = (): ReactElement => (
    <>
        <Alert type="success" headingLevel="h4" slim noIcon>
            {testText}
        </Alert>
        <Alert type="warning" headingLevel="h4" slim noIcon>
            {testText}
        </Alert>
        <Alert type="error" headingLevel="h4" slim noIcon>
            {testText}
        </Alert>
        <Alert type="info" headingLevel="h4" slim noIcon>
            {testText}
        </Alert>
    </>
);

export const HeadingLevels = (): ReactElement => (
    <>
        <h1>Heading Level 1</h1>
        <Alert type="info" heading="Heading level 2" headingLevel="h2">
            {testText}
        </Alert>
    </>
);
export const WithCTA = (): ReactElement => (
    <Alert
        type="warning"
        heading="Warning status"
        headingLevel="h4"
        cta={
            <Button type="button" outline>
                Click here
            </Button>
        }
    >
        {testText}
    </Alert>
);
