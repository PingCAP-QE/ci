# Guides

Welcome to the guides section of our CI repository. This directory contains documentation that provides step-by-step instructions for common tasks and procedures related to our CI/CD processes.

## Available Guides

### CI/CD

- [CI](./CI.md) - Comprehensive guide to PingCAP's CI system, including how to locate pipelines for specific repositories, modify and test pipelines, and the workflow for deploying changes from staging to production environments
- [Docker Build](./docker-build.md) - Instructions for building Docker images for PingCAP components from source code, with references to Dockerfile locations for different repositories

### Development Workflow

- [Cherry-Pick Pull Request](./cherry-pick-pull-request.md) - How to cherry-pick changes from one pull request to another branch using our helper script, with step-by-step instructions and conflict resolution guidance

### Reference

- [FAQ](./FAQ.md) - Frequently Asked Questions about our CI/CD infrastructure, covering topics such as pipeline troubleshooting, Prow bot usage, and CLA/DCO issues

## Contributing

If you'd like to add a new guide:

1. Create a new Markdown file in this directory with a descriptive name
2. Follow the established format and style of existing guides
3. Include clear step-by-step instructions with examples
4. Add a link to your new guide in this README

## Guide Format Recommendations

When writing guides, please consider the following structure:

1. **Introduction** - Briefly explain what the guide is about and why it's useful
2. **Prerequisites** - List any requirements or setup needed
3. **Step-by-Step Instructions** - Clear, numbered steps to complete the task
4. **Examples** - Provide practical examples to illustrate the process
5. **Troubleshooting** - Common issues and their solutions (if applicable)
6. **See Also** - Links to related guides or documentation

## Updating Guides

If you find outdated information or errors in any guide, please submit a pull request with your improvements.