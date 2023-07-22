import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { renderApp } from "../../../utils/CustomRenderUtils";
import { FacilityResource } from "../../../config/endpoints/dataDashboard";
import { mockSessionContext } from "../../../contexts/__mocks__/SessionContext";
import { MemberType } from "../../../hooks/UseOktaMemberships";
import {
    orgServer,
    receiversGenerator,
} from "../../../__mocks__/OrganizationMockServer";
import { mockUseOrganizationReceiversFeed } from "../../../hooks/network/Organizations/__mocks__/ReceiversHooks";
import { mockFilterManager } from "../../../hooks/filters/mocks/MockFilterManager";
import { mockUseOrgDeliveries } from "../../../hooks/network/History/__mocks__/DeliveryHooks";

import FacilitiesProvidersTable from "./FacilitiesProvidersTable";

const mockData: FacilityResource[] = [
    {
        facilityId: "12w3e4r5",
        name: "Sally Doctor",
        location: "San Diego, CA",
        facilityType: "provider",
        reportDate: "2022-09-28T22:21:33.801667",
    },
    {
        facilityId: "12w3e4r6",
        name: "AFC Urgent Care",
        location: "San Antonio, TX",
        facilityType: "facility",
        reportDate: "2022-09-28T22:21:33.801667",
    },
    {
        facilityId: "12w3e4r7",
        name: "SimpleReport",
        location: "Fairfield, CO",
        facilityType: "submitter",
        reportDate: "2022-09-28T22:21:33.801667",
    },
];

const mockReceivers = receiversGenerator(5);
const mockActiveReceiver = mockReceivers[0];

jest.mock("rest-hooks", () => ({
    ...jest.requireActual("rest-hooks"),
    useResource: () => {
        return mockData;
    },
    useController: () => {
        // fetch is destructured as fetchController in component
        return { fetch: () => mockData };
    },
    // Must return children when mocking, otherwise nothing inside renders
    NetworkErrorBoundary: ({ children }: { children: JSX.Element[] }) => {
        return <>{children}</>;
    },
}));

describe("FacilitiesProvidersTable", () => {
    beforeAll(() => orgServer.listen());
    afterEach(() => orgServer.resetHandlers());
    afterAll(() => orgServer.close());

    describe("useOrganizationReceiversFeed without data", () => {
        beforeEach(() => {
            // Mock our receiverServices feed data
            mockUseOrganizationReceiversFeed.mockReturnValue({
                activeService: undefined,
                loadingServices: false,
                services: [],
                setActiveService: () => {},
                isDisabled: false,
            });

            // Mock our SessionProvider's data
            mockSessionContext.mockReturnValue({
                oktaToken: {
                    accessToken: "TOKEN",
                },
                activeMembership: {
                    memberType: MemberType.RECEIVER,
                    parsedName: "testOrgNoReceivers",
                    service: "testReceiver",
                },
                dispatch: () => {},
                initialized: true,
                isUserAdmin: false,
                isUserReceiver: true,
                isUserSender: false,
                environment: "test",
            });

            // Mock the response from the Deliveries hook
            const mockUseOrgDeliveriesCallback = {
                fetchResults: () => Promise.resolve([]),
                filterManager: mockFilterManager,
            };
            mockUseOrgDeliveries.mockReturnValue(mockUseOrgDeliveriesCallback);

            // Render the component
            renderApp(<FacilitiesProvidersTable />);
        });

        test("if no activeService display NoServicesBanner", async () => {
            const heading = await screen.findByText(
                /Active Services unavailable/i
            );
            expect(heading).toBeInTheDocument();
            const message = await screen.findByText(
                /No valid receiver found for your organization/i
            );
            expect(message).toBeInTheDocument();
        });
    });
});

describe("FacilitiesProvidersTable", () => {
    describe("with receiver services and data", () => {
        beforeEach(() => {
            mockUseOrganizationReceiversFeed.mockReturnValue({
                activeService: mockActiveReceiver,
                loadingServices: false,
                services: mockReceivers,
                setActiveService: () => {},
                isDisabled: false,
            });

            // TODO: add mock callback once API is ready

            // Render the component
            renderApp(<FacilitiesProvidersTable />);
        });

        test("renders with no error", async () => {
            // Column headers render
            expect(screen.getByText("Name")).toBeInTheDocument();
            expect(screen.getByText("Location")).toBeInTheDocument();
            expect(screen.getByText("Facility type")).toBeInTheDocument();
            expect(
                screen.getByText("Most recent report date")
            ).toBeInTheDocument();
        });

        test("renders Facility type column with transformed name", async () => {
            expect(screen.getByText("ORDERING PROVIDER")).toBeInTheDocument();
            expect(screen.getByText("PERFORMING FACILITY")).toBeInTheDocument();
            expect(screen.getByText("SUBMITTER")).toBeInTheDocument();
        });

        describe("clicking on a name will render the appropriate detail page", () => {
            test("facility type provider", async () => {
                expect(screen.getByText("ORDERING PROVIDER")).toBeVisible();
                await userEvent.click(screen.getByText("Sally Doctor"));

                expect(screen.getByText("ORDERING PROVIDER")).toBeVisible();
            });

            test("facility type facility", async () => {
                expect(screen.getByText("PERFORMING FACILITY")).toBeVisible();
                await userEvent.click(screen.getByText("AFC Urgent Care"));

                expect(screen.getByText("PERFORMING FACILITY")).toBeVisible();
            });

            test("facility type submitter", async () => {
                expect(screen.getByText("SUBMITTER")).toBeVisible();
                await userEvent.click(screen.getByText("SimpleReport"));

                expect(screen.getByText("SUBMITTER")).toBeVisible();
            });
        });
    });

    describe("with no services", () => {
        beforeEach(() => {
            // Mock our receiverServices feed data
            mockUseOrganizationReceiversFeed.mockReturnValue({
                activeService: undefined,
                loadingServices: false,
                services: [],
                setActiveService: () => {},
                isDisabled: false,
            });

            // Mock our SessionProvider's data
            mockSessionContext.mockReturnValue({
                oktaToken: {
                    accessToken: "TOKEN",
                },
                activeMembership: {
                    memberType: MemberType.RECEIVER,
                    parsedName: "testOrgNoReceivers",
                    service: "testReceiver",
                },
                dispatch: () => {},
                initialized: true,
                isUserAdmin: false,
                isUserReceiver: true,
                isUserSender: false,
                environment: "test",
            });

            // TODO: Mock the response from the API once ready

            // Render the component
            renderApp(<FacilitiesProvidersTable />);
        });

        test("renders the NoServicesBanner message", async () => {
            const heading = await screen.findByText(
                "Active Services unavailable"
            );
            expect(heading).toBeInTheDocument();

            const message = await screen.findByText(
                "No valid receiver found for your organization"
            );
            expect(message).toBeInTheDocument();
        });
    });
});
