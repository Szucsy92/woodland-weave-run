import { createFileRoute } from "@tanstack/react-router";
import { ForestRunner } from "../components/ForestRunner";

export const Route = createFileRoute("/")({
  ssr: false,
  head: () => ({
    meta: [
      { title: "Woodland Weave Run – endless 3D forest runner" },
      { name: "description", content: "Run through an endless night forest, turn and dodge the trees." },
      { property: "og:title", content: "Woodland Weave Run – endless 3D forest runner" },
      { property: "og:description", content: "Run through an endless night forest, turn and dodge the trees." },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary_large_image" },
    ],
  }),
  component: ForestRunner,
});
