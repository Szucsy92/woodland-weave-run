import { createFileRoute } from "@tanstack/react-router";
import { ForestRunner } from "../components/ForestRunner";

export const Route = createFileRoute("/")({
  ssr: false,
  head: () => ({
    meta: [
      { title: "Erdei futás – 3D végtelen futójáték" },
      { name: "description", content: "Fuss a végtelen erdőben, kanyarodj és kerüld ki a fákat." },
      { property: "og:title", content: "Erdei futás – 3D végtelen futójáték" },
      { property: "og:description", content: "Fuss a végtelen erdőben, kanyarodj és kerüld ki a fákat." },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary_large_image" },
    ],
  }),
  component: ForestRunner,
});
